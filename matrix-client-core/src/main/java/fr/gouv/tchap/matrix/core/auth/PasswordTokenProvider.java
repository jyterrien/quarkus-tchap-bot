package fr.gouv.tchap.matrix.core.auth;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.gouv.tchap.matrix.auth.CredentialStore;
import fr.gouv.tchap.matrix.auth.Credentials;
import fr.gouv.tchap.matrix.auth.TokenProvider;
import fr.gouv.tchap.matrix.exception.AuthenticationException;
import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.exception.MatrixServerException;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Obtient le token d'accès par {@code /login} (identifiant + mot de passe) et
 * le garde valide.
 *
 * <ul>
 * <li>Premier appel : reprend les identifiants du {@link CredentialStore} s'il
 * en contient pour ce compte, sinon se connecte.</li>
 * <li>Token proche de l'expiration : renouvelé à l'avance, avant que le serveur
 * ne le refuse.</li>
 * <li>Token refusé par le serveur : renouvelé à la demande
 * ({@link #renew}).</li>
 * </ul>
 *
 * Un renouvellement utilise {@code /refresh} quand un token de renouvellement
 * est connu ; si le serveur le refuse, ou qu'il n'y en a pas, il se reconnecte
 * avec le mot de passe (en réutilisant le même device). Les erreurs
 * transitoires (réseau, 5xx, 429) ne déclenchent PAS de reconnexion : elles
 * sont transmises pour que l'appelant réessaie plus tard. Un refus définitif
 * des identifiants échoue avec une {@link AuthenticationException}.
 *
 * Les renouvellements sont sérialisés : des requêtes simultanées refusées
 * ensemble n'en déclenchent qu'un. Ni le mot de passe ni les tokens ne sont
 * journalisés.
 */
public final class PasswordTokenProvider implements TokenProvider {

	private static final System.Logger LOG = System.getLogger(PasswordTokenProvider.class.getName());
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String API_PREFIX = "/_matrix/client/v3";

	/** Marge avant l'expiration à partir de laquelle le token est renouvelé. */
	static final Duration RENEW_MARGIN = Duration.ofSeconds(60);

	private final HttpTransport transport;
	private final String userId;
	private final String password;
	private final CredentialStore store;
	private final String deviceName;
	private final Clock clock;

	private volatile Credentials current;
	/** Renouvellement en cours ; protégé par {@code this}. */
	private CompletableFuture<String> inflight;
	/**
	 * Vrai après un logout réussi : plus aucun token ne doit être fourni ni
	 * renouvelé (sinon une requête tardive rouvrirait une session aussitôt après la
	 * fermeture de l'ancienne).
	 */
	private volatile boolean loggedOut;

	public PasswordTokenProvider(HttpTransport transport, String userId, String password, CredentialStore store,
			String deviceName) {
		this(transport, userId, password, store, deviceName, Clock.systemUTC());
	}

	PasswordTokenProvider(HttpTransport transport, String userId, String password, CredentialStore store,
			String deviceName, Clock clock) {
		this.transport = Objects.requireNonNull(transport, "transport is required");
		this.userId = requireText(userId, "userId");
		this.password = requireText(password, "password");
		this.store = Objects.requireNonNull(store, "store is required");
		this.deviceName = deviceName;
		this.clock = clock;
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " is required");
		}
		return value;
	}

	@Override
	public CompletionStage<String> accessToken() {
		if (loggedOut) {
			return CompletableFuture.failedFuture(sessionClosed());
		}
		Credentials c = current;
		if (c != null && !isExpiring(c)) {
			return CompletableFuture.completedFuture(c.accessToken());
		}
		return renew(c == null ? null : c.accessToken());
	}

	@Override
	public CompletionStage<String> renew(String rejectedToken) {
		synchronized (this) {
			if (loggedOut) {
				return CompletableFuture.failedFuture(sessionClosed());
			}
			Credentials c = current;
			if (c != null && rejectedToken != null && !rejectedToken.equals(c.accessToken())) {
				// Un autre appel a déjà renouvelé le token refusé
				return CompletableFuture.completedFuture(c.accessToken());
			}
			if (inflight != null) {
				return inflight;
			}
			CompletableFuture<String> result = new CompletableFuture<>();
			inflight = result;
			CompletionStage<String> renewal;
			try {
				renewal = startRenewal(c);
			} catch (RuntimeException e) {
				renewal = CompletableFuture.failedFuture(e);
			}
			result.whenComplete((token, ex) -> {
				synchronized (this) {
					if (inflight == result) {
						inflight = null;
					}
				}
			});
			renewal.whenComplete((token, ex) -> {
				if (ex != null) {
					result.completeExceptionally(unwrap(ex));
				} else {
					result.complete(token);
				}
			});
			return result;
		}
	}

	/**
	 * Ferme la session ({@code POST /logout}) avec le token courant, puis oublie
	 * les identifiants (mémoire et magasin). Sans effet si ce fournisseur ne s'est
	 * pas connecté dans ce processus, ou s'il s'est déjà déconnecté.
	 *
	 * Un token expiré est d'abord renouvelé par {@code /refresh} — jamais par une
	 * nouvelle connexion, qui créerait une session pour la fermer aussitôt. Si le
	 * serveur ne reconnaît déjà plus la session (token et refresh token refusés),
	 * elle est considérée comme fermée. Une erreur transitoire (réseau, 5xx) fait
	 * échouer le logout et laisse les identifiants intacts : la session existe
	 * encore.
	 */
	@Override
	public CompletionStage<Void> logout() {
		CompletableFuture<String> pending;
		synchronized (this) {
			pending = inflight;
		}
		// Un renouvellement en cours peut remplacer les tokens : on attend qu'il se
		// termine
		CompletionStage<Void> settled = pending == null
				? CompletableFuture.<Void>completedFuture(null)
				: pending.<Void>handle((token, ex) -> null);
		return settled.thenCompose(ignored -> {
			Credentials c = current;
			if (loggedOut || c == null) {
				return CompletableFuture.<Void>completedFuture(null);
			}
			return logoutWith(c);
		});
	}

	private CompletionStage<Void> logoutWith(Credentials c) {
		return postLogout(c.accessToken()).exceptionallyCompose(ex -> {
			Throwable cause = unwrap(ex);
			if (!isTokenRejected(cause)) {
				return CompletableFuture.<Void>failedFuture(cause);
			}
			if (!c.hasRefreshToken()) {
				// Token refusé et rien pour le renouveler : le serveur a déjà fermé la session
				return CompletableFuture.<Void>completedFuture(null);
			}
			return refresh(c).thenCompose(this::postLogout).exceptionallyCompose(retryEx -> {
				Throwable retryCause = unwrap(retryEx);
				// Refresh token refusé, ou nouveau token refusé : la session n'existe plus
				return isDefinitiveRefusal(retryCause)
						? CompletableFuture.<Void>completedFuture(null)
						: CompletableFuture.<Void>failedFuture(retryCause);
			});
		}).thenCompose(done -> finishLogout());
	}

	private CompletionStage<Void> postLogout(String accessToken) {
		return transport.post(API_PREFIX + "/logout", accessToken, "{}").thenApply(body -> null);
	}

	/**
	 * La session est fermée : plus de token, et les identifiants mémorisés ne
	 * valent plus rien.
	 */
	private CompletionStage<Void> finishLogout() {
		loggedOut = true;
		current = null;
		LOG.log(INFO, "Logged out: the session was closed on the homeserver");
		CompletionStage<Void> cleared;
		try {
			cleared = store.clear();
		} catch (RuntimeException e) {
			cleared = CompletableFuture.failedFuture(e);
		}
		return cleared.<Void>handle((v, ex) -> {
			if (ex != null) {
				LOG.log(WARNING, "Could not clear the stored credentials: {0}", unwrap(ex).getMessage());
			}
			return null;
		});
	}

	private static AuthenticationException sessionClosed() {
		return new AuthenticationException("Session closed: the bot has logged out");
	}

	private static boolean isTokenRejected(Throwable cause) {
		return cause instanceof MatrixServerException server && server.isTokenRejected();
	}

	/**
	 * Identifiant du compte tel que le serveur le connaît, une fois connecté. Il
	 * peut différer de l'identifiant de connexion configuré.
	 */
	public Optional<String> serverUserId() {
		Credentials c = current;
		return c == null ? Optional.empty() : Optional.ofNullable(c.userId());
	}

	private boolean isExpiring(Credentials c) {
		return c.expiresAt() != null && !clock.instant().isBefore(c.expiresAt().minus(RENEW_MARGIN));
	}

	private CompletionStage<String> startRenewal(Credentials known) {
		if (known != null) {
			return renewFrom(known);
		}
		return loadStored().thenCompose(stored -> {
			if (stored.isEmpty()) {
				return login(null);
			}
			Credentials c = stored.get();
			current = c;
			return isExpiring(c) ? renewFrom(c) : CompletableFuture.completedFuture(c.accessToken());
		});
	}

	/**
	 * Identifiants mémorisés pour ce compte ; un magasin en panne équivaut à un
	 * magasin vide.
	 */
	private CompletionStage<Optional<Credentials>> loadStored() {
		CompletionStage<Optional<Credentials>> loaded;
		try {
			loaded = store.load();
		} catch (RuntimeException e) {
			loaded = CompletableFuture.failedFuture(e);
		}
		return loaded.handle((opt, ex) -> {
			if (ex != null) {
				LOG.log(WARNING, "Could not read stored credentials, logging in instead: {0}", unwrap(ex).getMessage());
				return Optional.<Credentials>empty();
			}
			return (opt == null ? Optional.<Credentials>empty() : opt)
					.filter(c -> c.login() == null || c.login().equalsIgnoreCase(userId));
		});
	}

	private CompletionStage<String> renewFrom(Credentials c) {
		if (!c.hasRefreshToken()) {
			return login(c.deviceId());
		}
		return refresh(c).exceptionallyCompose(ex -> {
			Throwable cause = unwrap(ex);
			if (!isDefinitiveRefusal(cause)) {
				return CompletableFuture.<String>failedFuture(cause);
			}
			LOG.log(INFO, "Refresh token refused by the homeserver, logging in again");
			return login(c.deviceId());
		});
	}

	private CompletionStage<String> refresh(Credentials c) {
		ObjectNode body = MAPPER.createObjectNode().put("refresh_token", c.refreshToken());
		return transport.post(API_PREFIX + "/refresh", null, body.toString()).thenCompose(json -> {
			JsonNode node = parse(json, "refresh");
			String accessToken = text(node, "access_token");
			if (accessToken == null) {
				throw new MatrixException("Invalid refresh response: missing access_token");
			}
			// Sans nouveau refresh_token dans la réponse, l'ancien reste valable
			String refreshToken = text(node, "refresh_token");
			return adopt(new Credentials(c.login(), c.userId(), c.deviceId(), accessToken,
					refreshToken != null ? refreshToken : c.refreshToken(), expiryOf(node)));
		});
	}

	private CompletionStage<String> login(String deviceId) {
		ObjectNode body = MAPPER.createObjectNode();
		body.put("type", "m.login.password");
		body.putObject("identifier").put("type", "m.id.user").put("user", userId);
		body.put("password", password);
		body.put("refresh_token", true);
		if (deviceId != null && !deviceId.isBlank()) {
			body.put("device_id", deviceId);
		}
		if (deviceName != null && !deviceName.isBlank()) {
			body.put("initial_device_display_name", deviceName);
		}
		return transport.post(API_PREFIX + "/login", null, body.toString()).handle((json, ex) -> {
			if (ex != null) {
				Throwable cause = unwrap(ex);
				if (isDefinitiveRefusal(cause)) {
					MatrixServerException server = (MatrixServerException) cause;
					throw new AuthenticationException("Login refused by the homeserver (HTTP " + server.statusCode()
							+ (server.errcode() == null ? "" : ", " + server.errcode()) + ")", cause);
				}
				throw new CompletionException(cause);
			}
			return json;
		}).thenCompose(json -> {
			JsonNode node = parse(json, "login");
			String accessToken = text(node, "access_token");
			if (accessToken == null) {
				throw new MatrixException("Invalid login response: missing access_token");
			}
			String loggedUser = text(node, "user_id");
			String loggedDevice = text(node, "device_id");
			return adopt(new Credentials(userId, loggedUser != null ? loggedUser : userId,
					loggedDevice != null ? loggedDevice : deviceId, accessToken, text(node, "refresh_token"),
					expiryOf(node)));
		});
	}

	/**
	 * Adopte les nouveaux identifiants puis les persiste AVANT de rendre le token :
	 * le token de renouvellement est à usage unique, le perdre ruinerait la session
	 * au prochain redémarrage. Un échec de persistance n'empêche pas de continuer.
	 */
	private CompletionStage<String> adopt(Credentials credentials) {
		current = credentials;
		CompletionStage<Void> saved;
		try {
			saved = store.save(credentials);
		} catch (RuntimeException e) {
			saved = CompletableFuture.failedFuture(e);
		}
		return saved.handle((v, ex) -> {
			if (ex != null) {
				LOG.log(WARNING, "Could not persist credentials (a restart will need a new login): {0}",
						unwrap(ex).getMessage());
			}
			return credentials.accessToken();
		});
	}

	/**
	 * Refus définitif du serveur : 4xx autre que 429 (voir
	 * MatrixServerException#isClientError).
	 */
	private static boolean isDefinitiveRefusal(Throwable cause) {
		return cause instanceof MatrixServerException server && server.isClientError();
	}

	private Instant expiryOf(JsonNode node) {
		JsonNode ms = node.path("expires_in_ms");
		return ms.canConvertToLong() && ms.asLong() > 0 ? clock.instant().plusMillis(ms.asLong()) : null;
	}

	private static String text(JsonNode node, String field) {
		String value = node.path(field).asText(null);
		return value == null || value.isBlank() ? null : value;
	}

	private static JsonNode parse(String json, String what) {
		try {
			return MAPPER.readTree(json);
		} catch (Exception e) {
			// Le corps n'est pas recopié : il contient des tokens
			throw new MatrixException("Invalid " + what + " response (not JSON)", e);
		}
	}

	private static Throwable unwrap(Throwable t) {
		Throwable cause = t;
		while (cause instanceof CompletionException && cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause;
	}
}
