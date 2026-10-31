package fr.gouv.tchap.matrix.core.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.event.MatrixEvent;
import fr.gouv.tchap.matrix.sync.SyncTokenStore;

import java.util.Collections;
import java.util.LinkedHashMap;
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
import java.util.function.Consumer;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;

/**
 * Boucle de long polling /sync.
 * Livraison at-least-once avec déduplication par event_id sur une fenêtre bornée.
 *
 * Journalisation via System.Logger : aucune dépendance, et redirigé automatiquement
 * vers le logging de Quarkus (JBoss LogManager) quand le module tourne dans Quarkus.
 */
public class SyncEngine {

    private static final System.Logger LOG = System.getLogger(SyncEngine.class.getName());

    /** Nombre d'event_id mémorisés pour la déduplication (les plus anciens sont oubliés). */
    static final int DEFAULT_DEDUP_CAPACITY = 10_000;

    private static final long SYNC_TIMEOUT_MS = 30_000;
    private static final long RETRY_DELAY_MS = 5_000;

    private final MatrixHttpApi api;
    private final SyncTokenStore tokenStore;
    private final Consumer<MatrixEvent> eventConsumer;
    private final SyncEventParser parser;
    private final int initialLimit;
    private final Set<String> processedEventIds;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScheduledExecutorService executor;
    private volatile String currentToken;

    public SyncEngine(MatrixHttpApi api,
                      SyncTokenStore tokenStore,
                      Consumer<MatrixEvent> eventConsumer) {
        this(api, tokenStore, eventConsumer, 0);
    }

    public SyncEngine(MatrixHttpApi api,
                      SyncTokenStore tokenStore,
                      Consumer<MatrixEvent> eventConsumer,
                      int initialLimit) {
        this(api, tokenStore, eventConsumer, initialLimit, DEFAULT_DEDUP_CAPACITY);
    }

    /** Visible pour les tests : capacité de dédup configurable. */
    SyncEngine(MatrixHttpApi api,
               SyncTokenStore tokenStore,
               Consumer<MatrixEvent> eventConsumer,
               int initialLimit,
               int dedupCapacity) {
        if (dedupCapacity < 1) {
            throw new IllegalArgumentException("dedupCapacity must be >= 1");
        }
        this.api = api;
        this.tokenStore = tokenStore;
        this.eventConsumer = eventConsumer;
        this.initialLimit = initialLimit;
        this.parser = new SyncEventParser(new ObjectMapper());
        this.processedEventIds = boundedSet(dedupCapacity);
    }

    /** Ensemble thread-safe à taille bornée : le plus ancien élément est évincé en premier. */
    private static Set<String> boundedSet(int capacity) {
        return Collections.newSetFromMap(Collections.synchronizedMap(
            new LinkedHashMap<String, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > capacity;
                }
            }));
    }

    public CompletionStage<Void> start() {
        if (!running.compareAndSet(false, true)) return CompletableFuture.completedFuture(null);
        return tokenStore.load().thenCompose(opt -> {
            currentToken = opt.orElse(null);
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
        if (ex != null) ex.shutdownNow();
        return CompletableFuture.completedFuture(null);
    }

    private void schedule(long delayMs) {
        if (!running.get()) return;
        try {
            executor.schedule(this::doSync, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // arrêt concurrent : l'executor est déjà fermé, rien à faire
            LOG.log(DEBUG, "Sync not rescheduled: engine is stopping");
        }
    }

    private void doSync() {
        if (!running.get()) return;
        api.sync(currentToken, SYNC_TIMEOUT_MS, initialLimit)
            .thenAccept(this::handleResponse)
            .exceptionally(ex -> {
                LOG.log(WARNING, "Sync failed, retrying in " + RETRY_DELAY_MS + " ms", unwrap(ex));
                schedule(RETRY_DELAY_MS);
                return null;
            });
    }

    private void handleResponse(String response) {
        String nextBatch = parser.extractNextBatch(response);
        if (nextBatch != null && !nextBatch.isBlank()) {
            List<MatrixEvent> events = parser.parseEvents(response);
            LOG.log(DEBUG, "Sync OK: next_batch={0}, {1} event(s)", nextBatch, events.size());
            for (MatrixEvent ev : events) {
                if (processedEventIds.add(ev.eventId())) {
                    deliver(ev);
                }
            }
            currentToken = nextBatch;
            tokenStore.save(nextBatch).exceptionally(ex -> {
                LOG.log(WARNING, "Could not save sync token", unwrap(ex));
                return null;
            });
        }
        schedule(0);
    }

    /** Isole le consumer : une exception de l'application ne doit pas casser la boucle. */
    private void deliver(MatrixEvent ev) {
        try {
            eventConsumer.accept(ev);
        } catch (Exception e) {
            LOG.log(ERROR, "Event consumer failed for event " + ev.eventId()
                + " in room " + ev.roomId(), e);
        }
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    public boolean isRunning() { return running.get(); }
}
