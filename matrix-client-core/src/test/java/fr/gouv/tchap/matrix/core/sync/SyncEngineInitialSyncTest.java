package fr.gouv.tchap.matrix.core.sync;

import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Vérifie que le filtre « pas d'historique » n'est appliqué qu'au premier /sync. */
class SyncEngineInitialSyncTest {

    /** Transport qui enregistre les chemins GET et répond avec un next_batch croissant. */
    static class SyncRecorder implements HttpTransport {
        final List<String> paths = new CopyOnWriteArrayList<>();
        final CountDownLatch twoCalls = new CountDownLatch(2);
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public CompletionStage<String> get(String path, String accessToken) {
            paths.add(path);
            twoCalls.countDown();
            return CompletableFuture.completedFuture(
                "{\"next_batch\":\"s" + counter.incrementAndGet() + "\"}");
        }

        @Override
        public CompletionStage<String> put(String path, String accessToken, String jsonBody) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<String> post(String path, String accessToken, String jsonBody) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void firstSyncUsesFilter_nextSyncUsesSinceWithoutFilter() throws Exception {
        SyncRecorder transport = new SyncRecorder();
        SyncEngine engine = new SyncEngine(
            new MatrixHttpApi(transport, "tok"),
            new InMemorySyncTokenStore(),
            ev -> {},
            0);

        engine.start().toCompletableFuture().join();
        try {
            assertTrue(transport.twoCalls.await(5, TimeUnit.SECONDS), "deux /sync attendus");
        } finally {
            engine.stop().toCompletableFuture().join();
        }

        String first = transport.paths.get(0);
        String second = transport.paths.get(1);
        assertTrue(first.contains("filter="), first);
        assertFalse(first.contains("since="), first);
        assertTrue(second.contains("since=s1"), second);
        assertFalse(second.contains("filter="), second);
    }

    @Test
    void existingTokenFromStore_skipsFilter() throws Exception {
        SyncRecorder transport = new SyncRecorder();
        InMemorySyncTokenStore store = new InMemorySyncTokenStore();
        store.save("stored").toCompletableFuture().join();
        SyncEngine engine = new SyncEngine(
            new MatrixHttpApi(transport, "tok"), store, ev -> {}, 0);

        engine.start().toCompletableFuture().join();
        try {
            assertTrue(transport.twoCalls.await(5, TimeUnit.SECONDS));
        } finally {
            engine.stop().toCompletableFuture().join();
        }

        assertTrue(transport.paths.get(0).contains("since=stored"));
        assertFalse(transport.paths.get(0).contains("filter="));
    }
}
