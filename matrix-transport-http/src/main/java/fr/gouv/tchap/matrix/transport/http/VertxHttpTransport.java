package fr.gouv.tchap.matrix.transport.http;

import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.exception.RateLimitException;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Implémentation HttpTransport basée sur Vert.x WebClient.
 * Recommandée en environnement Quarkus : le Vertx est déjà géré par le runtime.
 */
public class VertxHttpTransport implements HttpTransport {

    private final Vertx vertx;
    private final WebClient client;
    private final String homeserverUrl;

    public VertxHttpTransport(String homeserverUrl) {
        this(Vertx.vertx(), homeserverUrl);
    }

    public VertxHttpTransport(Vertx vertx, String homeserverUrl) {
        this.vertx = vertx;
        this.client = WebClient.create(vertx, new WebClientOptions()
            .setConnectTimeout(30_000)
            .setKeepAlive(true));
        this.homeserverUrl = homeserverUrl.endsWith("/")
            ? homeserverUrl.substring(0, homeserverUrl.length() - 1)
            : homeserverUrl;
    }

    @Override
    public CompletionStage<String> get(String path, String accessToken) {
        HttpRequest<Buffer> req = client.getAbs(homeserverUrl + path)
            .putHeader("Authorization", "Bearer " + accessToken);
        return send(req);
    }

    @Override
    public CompletionStage<String> put(String path, String accessToken, String jsonBody) {
        HttpRequest<Buffer> req = client.putAbs(homeserverUrl + path)
            .putHeader("Authorization", "Bearer " + accessToken)
            .putHeader("Content-Type", "application/json");
        return send(req.sendBuffer(Buffer.buffer(jsonBody == null ? "" : jsonBody)));
    }

    private CompletionStage<String> send(HttpRequest<Buffer> req) {
        return send(req.send());
    }

    private CompletionStage<String> send(io.vertx.core.Future<HttpResponse<Buffer>> future) {
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
                String raw = resp.getHeader("Retry-After");
                long retryMs = 1000L;
                if (raw != null) {
                    try { retryMs = Math.max(0L, Long.parseLong(raw.trim())) * 1000L; }
                    catch (NumberFormatException ignored) {}
                }
                result.completeExceptionally(new RateLimitException("HTTP 429 rate limited", retryMs));
            } else if (code >= 400) {
                result.completeExceptionally(new MatrixException(
                    "HTTP " + code + ": " + resp.bodyAsString()));
            } else {
                result.complete(resp.bodyAsString());
            }
        });
        return result;
    }
}
