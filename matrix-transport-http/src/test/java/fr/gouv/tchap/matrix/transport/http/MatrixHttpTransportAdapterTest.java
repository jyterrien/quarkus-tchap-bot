package fr.gouv.tchap.matrix.transport.http;

import fr.gouv.tchap.matrix.transport.HttpTransport;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MatrixHttpTransportAdapterTest {

    @Test
    void get_delegatesToHttpTransport() {
        AtomicReference<String> seenPath = new AtomicReference<>();
        AtomicReference<String> seenToken = new AtomicReference<>();
        HttpTransport delegate = new HttpTransport() {
            @Override public CompletionStage<String> get(String path, String accessToken) {
                seenPath.set(path);
                seenToken.set(accessToken);
                return CompletableFuture.completedFuture("body");
            }
            @Override public CompletionStage<String> put(String path, String accessToken, String jsonBody) {
                return CompletableFuture.completedFuture("ok");
            }
        };

        MatrixHttpTransportAdapter adapter = new MatrixHttpTransportAdapter(delegate);
        String result = adapter.get("/x", "tok").toCompletableFuture().join();

        assertEquals("body", result);
        assertEquals("/x", seenPath.get());
        assertEquals("tok", seenToken.get());
    }

    @Test
    void put_delegatesToHttpTransport() {
        AtomicReference<String> seenBody = new AtomicReference<>();
        HttpTransport delegate = new HttpTransport() {
            @Override public CompletionStage<String> get(String path, String accessToken) {
                return CompletableFuture.completedFuture("");
            }
            @Override public CompletionStage<String> put(String path, String accessToken, String jsonBody) {
                seenBody.set(jsonBody);
                return CompletableFuture.completedFuture("ok");
            }
        };

        MatrixHttpTransportAdapter adapter = new MatrixHttpTransportAdapter(delegate);
        adapter.put("/y", "tok", "{}").toCompletableFuture().join();

        assertEquals("{}", seenBody.get());
    }
}
