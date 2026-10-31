
package fr.gouv.tchap.matrix.transport.http;

import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.exception.RateLimitException;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Teste VertxHttpTransport contre un vrai serveur HTTP local (Vert.x, port aléatoire).
 * Aucun mock : on vérifie ce qui part réellement sur le fil.
 */
class VertxHttpTransportTest {

    private static Vertx vertx;
    private HttpServer server;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() {
        vertx.close().toCompletionStage().toCompletableFuture().join();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close().toCompletionStage().toCompletableFuture().join();
            server = null;
        }
    }

    /** Démarre un serveur local et renvoie son URL de base. */
    private String startServer(Handler<HttpServerRequest> handler) {
        server = vertx.createHttpServer().requestHandler(handler);
        server.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().join();
        return "http://127.0.0.1:" + server.actualPort();
    }

    private static Throwable failureOf(java.util.concurrent.CompletionStage<String> stage) {
        CompletionException ex = assertThrows(CompletionException.class,
            () -> stage.toCompletableFuture().join());
        return ex.getCause();
    }

    @Test
    void get_sendsBearerTokenAndReturnsBody() {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> uri = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();

        String url = startServer(req -> {
            method.set(req.method().name());
            uri.set(req.uri());
            auth.set(req.getHeader("Authorization"));
            req.response().setStatusCode(200).end("{\"next_batch\":\"s1\"}");
        });

        String body = new VertxHttpTransport(vertx, url)
            .get("/_matrix/client/v3/sync?timeout=0", "tok")
            .toCompletableFuture().join();

        assertEquals("{\"next_batch\":\"s1\"}", body);
        assertEquals("GET", method.get());
        assertEquals("/_matrix/client/v3/sync?timeout=0", uri.get());
        assertEquals("Bearer tok", auth.get());
    }

    @Test
    void put_sendsJsonBodyAndContentType() {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();

        String url = startServer(req -> req.bodyHandler(buf -> {
            method.set(req.method().name());
            contentType.set(req.getHeader("Content-Type"));
            auth.set(req.getHeader("Authorization"));
            receivedBody.set(buf.toString());
            req.response().setStatusCode(200).end("{\"event_id\":\"$e1\"}");
        }));

        String body = new VertxHttpTransport(vertx, url)
            .put("/rooms/r/send/m.room.message/t1", "tok", "{\"body\":\"hi\"}")
            .toCompletableFuture().join();

        assertEquals("{\"event_id\":\"$e1\"}", body);
        assertEquals("PUT", method.get());
        assertEquals("application/json", contentType.get());
        assertEquals("Bearer tok", auth.get());
        assertEquals("{\"body\":\"hi\"}", receivedBody.get());
    }

    @Test
    void homeserverUrl_trailingSlashIsIgnored() {
        AtomicReference<String> uri = new AtomicReference<>();
        String url = startServer(req -> {
            uri.set(req.uri());
            req.response().end("ok");
        });

        new VertxHttpTransport(vertx, url + "/")
            .get("/ping", "tok").toCompletableFuture().join();

        assertEquals("/ping", uri.get());
    }

    @Test
    void status429_withRetryAfter_mapsToRateLimitExceptionInMillis() {
        String url = startServer(req ->
            req.response().setStatusCode(429).putHeader("Retry-After", "2").end("{}"));

        Throwable cause = failureOf(new VertxHttpTransport(vertx, url).get("/x", "tok"));

        RateLimitException rle = assertInstanceOf(RateLimitException.class, cause);
        assertEquals(2000L, rle.retryAfterMillis());
    }

    @Test
    void status429_withoutRetryAfter_defaultsToOneSecond() {
        String url = startServer(req -> req.response().setStatusCode(429).end("{}"));

        Throwable cause = failureOf(new VertxHttpTransport(vertx, url).get("/x", "tok"));

        RateLimitException rle = assertInstanceOf(RateLimitException.class, cause);
        assertEquals(1000L, rle.retryAfterMillis());
    }

    @Test
    void status429_withHttpDateRetryAfter_fallsBackToOneSecond() {
        String url = startServer(req -> req.response().setStatusCode(429)
            .putHeader("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT").end("{}"));

        Throwable cause = failureOf(new VertxHttpTransport(vertx, url).get("/x", "tok"));

        assertEquals(1000L, assertInstanceOf(RateLimitException.class, cause).retryAfterMillis());
    }

    @Test
    void status4xx5xx_mapsToMatrixExceptionWithStatusAndBody() {
        String url = startServer(req ->
            req.response().setStatusCode(500).end("{\"errcode\":\"M_UNKNOWN\"}"));

        Throwable cause = failureOf(new VertxHttpTransport(vertx, url).get("/x", "tok"));

        assertInstanceOf(MatrixException.class, cause);
        assertFalse(cause instanceof RateLimitException);
        assertTrue(cause.getMessage().contains("HTTP 500"));
        assertTrue(cause.getMessage().contains("M_UNKNOWN"));
    }

    @Test
    void connectionRefused_mapsToMatrixException() {
        // Port 1 : personne n'écoute, la connexion est refusée immédiatement.
        Throwable cause = failureOf(new VertxHttpTransport(vertx, "http://127.0.0.1:1")
            .get("/x", "tok"));

        assertInstanceOf(MatrixException.class, cause);
    }
}
