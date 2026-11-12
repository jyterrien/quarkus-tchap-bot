package fr.gouv.tchap.matrix.core.sync;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.event.MatrixEvent;
import fr.gouv.tchap.matrix.exception.AuthenticationException;
import fr.gouv.tchap.matrix.exception.RateLimitException;
import fr.gouv.tchap.matrix.sync.SyncHealth;
import fr.gouv.tchap.matrix.sync.SyncTokenStore;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Boucle de long polling /sync. Livraison at-least-once avec déduplication par
 * event_id sur une fenêtre bornée. Journalisation via System.Logger : aucune
 * dépendance, et redirigé automatiquement vers le logging de Quarkus (JBoss
 * LogManager) quand le module tourne dans Quarkus.
 */
public class SyncEngine {

	private static final System.Logger LOG = System.getLogger(SyncEngine.class.getName());

	public static final long DEFAULT_SYNC_TIMEOUT_MS = 30_000;

	/**
	 * Nombre d'event_id mémorisés pour la déduplication (les plus anciens sont
	 * oubliés).
	 */
	static final int DEFAULT_DEDUP_CAPACITY = 10_000;

	/** Délai avant nouvel essai après une erreur quelconque. */
	private static final long RETRY_DELAY_MS = 5_000;

	/** Bornes appliquées au délai demandé par le serveur lors d'un 429. */
	private static final long MIN_RATE_LIMIT_DELAY_MS = 1_000;
	private static final long MAX_RATE_LIMIT_DELAY_MS = 300_000;

	private final MatrixHttpApi api;
	private final SyncTokenStore tokenStore;
	private final Consumer<MatrixEvent> eventConsumer;
	private final Consumer<String> inviteConsumer; // null = invitations ignorées
	private final SyncEventParser parser;
	private final int initialLimit;
	private final long syncTimeoutMs;
	private final Set<String> processedEventIds;

	private final AtomicBoolean running = new AtomicBoolean(false);
	private volatile ScheduledExecutorService executor;
	private volatile String currentToken;

	// Santé de la boucle (voir health())
	private volatile Instant startedAt;
	private volatile Instant lastSuccess;
	private volatile String lastError;
	private final AtomicInteger consecutiveFailures = new AtomicInteger();

	/**
	 * Appelé une fois si la boucle s'arrête d'elle-même sur une erreur définitive.
	 */
	private volatile Consumer<Throwable> fatalErrorHandler = t -> {
	};

	/**
	 * Salons suivis. Vide = tous les salons du compte. Remplacé atomiquement,
	 * jamais modifié.
	 */
	private volatile Set<String> roomFilter = Collections.emptySet();

	public SyncEngine(MatrixHttpApi api, SyncTokenStore tokenStore, Consumer<MatrixEvent> eventConsumer) {
		this(api, tokenStore, eventConsumer, 0);
	}

	public SyncEngine(MatrixHttpApi api, SyncTokenStore tokenStore, Consumer<MatrixEvent> eventConsumer,
			int initialLimit) {
		this(api, tokenStore, eventConsumer, null, initialLimit, DEFAULT_SYNC_TIMEOUT_MS);
	}

	/**
	 * @param inviteConsumer
	 *            reçoit l'id de chaque salon où le bot est invité ; peut être null
	 * @param syncTimeoutMs
	 *            durée du long polling, en millisecondes (&gt; 0)
	 */
	public SyncEngine(MatrixHttpApi api, SyncTokenStore tokenStore, Consumer<MatrixEvent> eventConsumer,
			Consumer<String> inviteConsumer, int initialLimit, long syncTimeoutMs) {
		this(api, tokenStore, eventConsumer, inviteConsumer, initialLimit, syncTimeoutMs, DEFAULT_DEDUP_CAPACITY);
	}

	/** Visible pour les tests : capacité de dédup configurable. */
	SyncEngine(MatrixHttpApi api, SyncTokenStore tokenStore, Consumer<MatrixEvent> eventConsumer, int initialLimit,
			int dedupCapacity) {
		this(api, tokenStore, eventConsumer, null, initialLimit, DEFAULT_SYNC_TIMEOUT_MS, dedupCapacity);
	}

	SyncEngine(MatrixHttpApi api, SyncTokenStore tokenStore, Consumer<MatrixEvent> eventConsumer,
			Consumer<String> inviteConsumer, int initialLimit, long syncTimeoutMs, int dedupCapacity) {
		if (dedupCapacity < 1) {
			throw new IllegalArgumentException("dedupCapacity must be >= 1");
		}
		if (syncTimeoutMs <= 0) {
			throw new IllegalArgumentException("syncTimeoutMs must be > 0, was " + syncTimeoutMs);
		}
		this.api = api;
		this.tokenStore = tokenStore;
		this.eventConsumer = eventConsumer;
		this.inviteConsumer = inviteConsumer;
		this.initialLimit = initialLimit;
		this.syncTimeoutMs = syncTimeoutMs;
		this.parser = new SyncEventParser(new ObjectMapper());
		this.processedEventIds = boundedSet(dedupCapacity);
	}

	/**
	 * Ensemble thread-safe à taille bornée : le plus ancien élément est évincé en
	 * premier.
	 */
	private static Set<String> boundedSet(int capacity) {
		return Collections.newSetFromMap(Collections.synchronizedMap(new LinkedHashMap<String, Boolean>() {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
				return size() > capacity;
			}
		}));
	}

	public CompletionStage<Void> start() {
		if (!running.compareAndSet(false, true))
			return CompletableFuture.completedFuture(null);
		return tokenStore.load().thenCompose(opt -> {
			currentToken = opt.orElse(null);
			lastSuccess = null;
			lastError = null;
			consecutiveFailures.set(0);
			startedAt = Instant.now();
			executor = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "tchap-sync");
				t.setDaemon(true);
				return t;
			});
			schedule(0);
			return CompletableFuture.completedFuture(null);
		});
	}

	public CompletionStage<Void> stop() {
		running.set(false);
		ScheduledExecutorService ex = executor;
		if (ex != null)
			ex.shutdownNow();
		return CompletableFuture.completedFuture(null);
	}

	/**
	 * Visible pour les tests : permet d'observer le délai demandé sans attendre
	 * réellement.
	 */
	void schedule(long delayMs) {
		if (!running.get())
			return;
		try {
			executor.schedule(this::doSync, delayMs, TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException e) {
			// arrêt concurrent : l'executor est déjà fermé, rien à faire
			LOG.log(DEBUG, "Sync not rescheduled: engine is stopping");
		}
	}

	/**
	 * Déclare la boucle arrêtée par une erreur définitive (ex. identifiants
	 * refusés) : plus aucune requête, et {@code handler} est prévenu pour que le
	 * client passe en échec.
	 */
	public void onFatalError(Consumer<Throwable> handler) {
		this.fatalErrorHandler = handler == null ? t -> {
		} : handler;
	}

	private void fail(Throwable cause) {
		LOG.log(ERROR, "Sync stopped: " + cause.getMessage(), cause);
		running.set(false);
		ScheduledExecutorService ex = executor;
		if (ex != null)
			ex.shutdownNow();
		fatalErrorHandler.accept(cause);
	}

	private void doSync() {
		if (!running.get())
			return;
		api.sync(currentToken, syncTimeoutMs, initialLimit, roomFilter).thenAccept(this::handleResponse)
				.exceptionally(ex -> {
					Throwable cause = unwrap(ex);
					consecutiveFailures.incrementAndGet();
					lastError = describe(cause);
					if (cause instanceof AuthenticationException) {
						// Identifiants refusés : réessayer ne servirait à rien, et marteler le serveur
						// (nouveau login toutes les 5 s) risque de verrouiller le compte
						fail(cause);
						return null;
					}
					long delay = retryDelayMs(cause);
					if (cause instanceof RateLimitException) {
						LOG.log(WARNING, "Rate limited by homeserver, retrying in " + delay + " ms");
					} else {
						LOG.log(WARNING, "Sync failed, retrying in " + delay + " ms", cause);
					}
					schedule(delay);
					return null;
				});
	}

	private void handleResponse(String response) {
		String nextBatch = parser.extractNextBatch(response);
		if (nextBatch != null && !nextBatch.isBlank()) {
			List<MatrixEvent> events = parser.parseEvents(response);
			LOG.log(DEBUG, "Sync OK: next_batch={0}, {1} event(s)", nextBatch, events.size());
			for (MatrixEvent ev : events) {
				// Le serveur filtre déjà ; on ne se fie pas à lui pour l'isolation par salon
				if (!isWatched(ev.roomId()))
					continue;
				if (processedEventIds.add(ev.eventId())) {
					deliver(ev);
				}
			}
			handleInvites(response);
			currentToken = nextBatch;
			tokenStore.save(nextBatch).exceptionally(ex -> {
				LOG.log(WARNING, "Could not save sync token", unwrap(ex));
				return null;
			});
		}
		// Réponse traitée jusqu'au bout : la boucle progresse
		lastSuccess = Instant.now();
		lastError = null;
		consecutiveFailures.set(0);
		schedule(0);
	}

	/**
	 * Instantané de la santé de la boucle. Une boucle bloquée (requête qui ne rend
	 * jamais la main) se reconnaît à une {@code lastSuccess} qui vieillit sans
	 * qu'aucune erreur ne soit comptée.
	 */
	public SyncHealth health() {
		return new SyncHealth(startedAt, lastSuccess, consecutiveFailures.get(), lastError);
	}

	private static String describe(Throwable t) {
		String message = t.getMessage();
		String text = t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
		return text.length() > 200 ? text.substring(0, 200) + "…" : text;
	}

	/**
	 * Isole le consumer : une exception de l'application ne doit pas casser la
	 * boucle.
	 */
	private void deliver(MatrixEvent ev) {
		try {
			eventConsumer.accept(ev);
		} catch (Exception e) {
			LOG.log(ERROR, "Event consumer failed for event " + ev.eventId() + " in room " + ev.roomId(), e);
		}
	}

	private void handleInvites(String response) {
		if (inviteConsumer == null)
			return;
		for (String roomId : parser.parseInvitedRoomIds(response)) {
			if (!isWatched(roomId))
				continue;
			try {
				inviteConsumer.accept(roomId);
			} catch (Exception e) {
				LOG.log(ERROR, "Invite handler failed for room " + roomId, e);
			}
		}
	}

	/**
	 * Délai avant le prochain essai : pour un 429, le délai demandé par le serveur
	 * (borné entre 1 s et 5 min) ; sinon un délai fixe de 5 s.
	 */
	static long retryDelayMs(Throwable cause) {
		if (cause instanceof RateLimitException rl) {
			return Math.min(Math.max(rl.retryAfterMillis(), MIN_RATE_LIMIT_DELAY_MS), MAX_RATE_LIMIT_DELAY_MS);
		}
		return RETRY_DELAY_MS;
	}

	private static Throwable unwrap(Throwable t) {
		while (t instanceof CompletionException && t.getCause() != null) {
			t = t.getCause();
		}
		return t;
	}

	/**
	 * Restreint le suivi à ces salons (identifiants résolus). Collection vide :
	 * tous les salons. Pris en compte dès le prochain /sync ; peut être appelé
	 * avant ou après start().
	 */
	public void setRoomFilter(Collection<String> roomIds) {
		this.roomFilter = roomIds == null || roomIds.isEmpty()
				? Collections.emptySet()
				: Collections.unmodifiableSet(new LinkedHashSet<>(roomIds));
	}

	private boolean isWatched(String roomId) {
		Set<String> filter = roomFilter;
		return filter.isEmpty() || filter.contains(roomId);
	}

	public boolean isRunning() {
		return running.get();
	}
}
