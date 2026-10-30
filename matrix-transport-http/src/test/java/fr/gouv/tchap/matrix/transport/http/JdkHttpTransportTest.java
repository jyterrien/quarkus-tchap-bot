package fr.gouv.tchap.matrix.transport.http;

import com.sun.net.httpserver.HttpServer;
import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.exception.RateLimitException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class JdkHttpTransportTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void get_returnsBodyOn200() {
        server.createContext("/_matrix/client/v3/sync", exchange -> {
            byte[] body = "{\"next_batch\":\"abc\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });
        server.start();

        JdkHttpTransport transport = new JdkHttpTransport(baseUrl);
        String body = transport.get("/_matrix/client/v3/sync?timeout=0", "token")
            .toCompletableFuture().join();

        assertTrue(body.contains("next_batch"));
    }

    @Test
    void put_sendsJsonBody() {
        StringBuilder received = new StringBuilder();
        server.createContext("/send", exchange -> {
            received.append(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"event_id\":\"$abc\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });
        server.start();

        JdkHttpTransport transport = new JdkHttpTransport(baseUrl);
        String response = transport.put("/send", "token", "{\"msgtype\":\"m.text\"}")
            .toCompletableFuture().join();

        assertEquals("{\"msgtype\":\"m.text\"}", received.toString());
        assertTrue(response.contains("event_id"));
    }

    @Test
    void maps429ToRateLimitException() {
        server.createContext("/limited", exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "2");
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        server.start();

        JdkHttpTransport transport = new JdkHttpTransport(baseUrl);
        CompletionException ex = assertThrows(CompletionException.class, () ->
            transport.get("/limited", "token").toCompletableFuture().join());

        assertTrue(ex.getCause() instanceof RateLimitException);
        assertEquals(2000L, ((RateLimitException) ex.getCause()).retryAfterMillis());
    }

    @Test
    void mapsErrorToMatrixException() {
        server.createContext("/err", exchange -> {
            byte[] body = "{\"errcode\":\"M_FORBIDDEN\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });
        server.start();

        JdkHttpTransport transport = new JdkHttpTransport(baseUrl);
        CompletionException ex = assertThrows(CompletionException.class, () ->
            transport.get("/err", "token").toCompletableFuture().join());

        assertTrue(ex.getCause() instanceof MatrixException);
    }
}
