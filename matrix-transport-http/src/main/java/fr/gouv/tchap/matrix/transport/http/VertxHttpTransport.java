package fr.gouv.tchap.matrix.transport.http;

import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.exception.RateLimitException;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static java.lang.System.Logger.Level.WARNING;

/**
 * Implémentation HttpTransport basée sur Vert.x WebClient.
 *
 * Deux modes de propriété :
 * <ul>
 *   <li>{@link #VertxHttpTransport(Vertx, String)} : le Vertx appartient à l'appelant
 *       (ex. celui de Quarkus). {@link #close()} ne ferme que le WebClient.</li>
 *   <li>{@link #VertxHttpTransport(String)} : le transport crée son propre Vertx
 *       et le ferme dans {@link #close()}.</li>
 * </ul>
 */
public class VertxHttpTransport implements HttpTransport {

    private static final System.Logger LOG = System.getLogger(VertxHttpTransport.class.getName());
    private static final long CLOSE_TIMEOUT_SEC = 10;

    private final Vertx vertx;
    private final boolean ownsVertx;
    private final WebClient client;
    private final String homeserverUrl;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** Crée et possède son propre Vertx : appeler {@link #close()} pour l'arrêter. */
    public VertxHttpTransport(String homeserverUrl) {
        // normalize() est évalué avant Vertx.vertx() : une URL invalide ne laisse aucun Vertx orphelin
        this(normalize(homeserverUrl), Vertx.vertx(), true);
    }

    /** Utilise un Vertx fourni, qui reste sous la responsabilité de l'appelant. */
    public VertxHttpTransport(Vertx vertx, String homeserverUrl) {
        this(normalize(homeserverUrl), Objects.requireNonNull(vertx, "vertx is required"), false);
    }

    /** Visible pour les tests. {@code normalizedUrl} est déjà validée et sans « / » final. */
    VertxHttpTransport(String normalizedUrl, Vertx vertx, boolean ownsVertx) {
        this.homeserverUrl = normalizedUrl;
        this.vertx = vertx;
        this.ownsVertx = ownsVertx;
        this.client = WebClient.create(vertx, new WebClientOptions()
            .setConnectTimeout(30_000)
            .setKeepAlive(true));
    }

    private static String normalize(String homeserverUrl) {
        if (homeserverUrl == null || homeserverUrl.isBlank()) {
            throw new IllegalArgumentException("homeserverUrl is required");
        }
        return homeserverUrl.endsWith("/")
            ? homeserverUrl.substring(0, homeserverUrl.length() - 1)
            : homeserverUrl;
    }

    @Override
    public CompletionStage<String> get(String path, String accessToken) {
        return call(() -> client.getAbs(homeserverUrl + path)
            .putHeader("Authorization", "Bearer " + accessToken)
            .send());
    }

    @Override
    public CompletionStage<String> put(String path, String accessToken, String jsonBody) {
        return call(() -> client.putAbs(homeserverUrl + path)
            .putHeader("Authorization", "Bearer " + accessToken)
            .putHeader("Content-Type", "application/json")
            .sendBuffer(body(jsonBody)));
    }

    @Override
    public CompletionStage<String> post(String path, String accessToken, String jsonBody) {
        return call(() -> client.postAbs(homeserverUrl + path)
            .putHeader("Authorization", "Bearer " + accessToken)
            .putHeader("Content-Type", "application/json")
            .sendBuffer(body(jsonBody)));
    }

    /**
     * Ferme le WebClient, puis le Vertx si ce transport en est propriétaire.
     * Idempotent. Les appels suivants échouent avec une MatrixException.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        client.close();
        if (!ownsVertx) return;

        Future<Void> vertxClosed = vertx.close();
        // Ne jamais bloquer un thread Vert.x en attendant la fermeture (risque d'interblocage)
        if (Context.isOnVertxThread()) return;
        try {
            vertxClosed.toCompletionStage().toCompletableFuture()
                .get(CLOSE_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.log(WARNING, "Error while closing Vert.x", e);
        }
    }

    private static Buffer body(String jsonBody) {
        return Buffer.buffer(jsonBody == null ? "" : jsonBody);
    }

    private CompletionStage<String> call(Supplier<Future<HttpResponse<Buffer>>> action) {
        if (closed.get()) return failed(new MatrixException("Transport is closed"));
        try {
            return handle(action.get());
        } catch (RuntimeException e) {
            // Fermeture concurrente : Vert.x peut lever l'exception de façon synchrone
            return failed(new MatrixException("Vert.x transport error: " + e.getMessage(), e));
        }
    }

    private static CompletionStage<String> failed(Throwable t) {
        CompletableFuture<String> f = new CompletableFuture<>();
        f.completeExceptionally(t);
        return f;
    }

    private CompletionStage<String> handle(Future<HttpResponse<Buffer>> future) {
        CompletableFuture<String> result = new CompletableFuture<>();
        future.onComplete(ar -> {
            if (ar.failed()) {
                result.completeExceptionally(new MatrixException(
                    "Vert.x transport error: " + ar.cause().getMessage(), ar.cause()));
                return;
            }
            HttpResponse<Buffer> resp = ar.result();
            int code = resp.statusCode();
            if (code == 429) {
                result.completeExceptionally(
                    new RateLimitException("HTTP 429 rate limited", parseRetryAfterMillis(resp)));
            } else if (code >= 400) {
                result.completeExceptionally(new MatrixException(
                    "HTTP " + code + ": " + resp.bodyAsString()));
            } else {
                result.complete(resp.bodyAsString());
            }
        });
        return result;
    }

    /**
     * Délai avant réessai d'un 429, par ordre de priorité :
     * 1. champ JSON {@code retry_after_ms} du corps (spec Matrix, M_LIMIT_EXCEEDED) ;
     * 2. en-tête Retry-After en secondes (RFC 9110) ;
     * 3. 1 s par défaut (absent, illisible ou HTTP-date).
     */
    private static long parseRetryAfterMillis(HttpResponse<Buffer> resp) {
        Long fromBody = retryAfterFromBody(resp);
        if (fromBody != null) return fromBody;
        String raw = resp.getHeader("Retry-After");
        if (raw == null) return 1000L;
        try {
            return Math.max(0L, Long.parseLong(raw.trim())) * 1000L;
        } catch (NumberFormatException e) {
            return 1000L;
        }
    }

    private static Long retryAfterFromBody(HttpResponse<Buffer> resp) {
        try {
            Buffer body = resp.body();
            if (body == null) return null;
            Long ms = new JsonObject(body).getLong("retry_after_ms");
            return ms == null ? null : Math.max(0L, ms);
        } catch (RuntimeException e) {
            // corps vide, non JSON, ou champ d'un autre type : on retombe sur l'en-tête
            return null;
        }
    }
}
