package fr.gouv.tchap.matrix.core.client;

import fr.gouv.tchap.matrix.core.sync.InMemorySyncTokenStore;
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

class DefaultMatrixClientAutoJoinTest {

    /** Le premier /sync contient une invitation, les suivants rien. */
    static class FakeServer implements HttpTransport {
        final CountDownLatch syncs = new CountDownLatch(2);
        final CountDownLatch joined = new CountDownLatch(1);
        final List<String> posts = new CopyOnWriteArrayList<>();
        private final AtomicInteger n = new AtomicInteger();

        @Override
        public CompletionStage<String> get(String path, String accessToken) {
            int i = n.incrementAndGet();
            syncs.countDown();
            String body = i == 1
                ? "{\"next_batch\":\"s1\",\"rooms\":{\"invite\":{\"!invited:s\":{}}}}"
                : "{\"next_batch\":\"s" + i + "\"}";
            return CompletableFuture.completedFuture(body);
        }

        @Override
        public CompletionStage<String> post(String path, String accessToken, String jsonBody) {
            posts.add(path);
            joined.countDown();
            return CompletableFuture.completedFuture("{\"room_id\":\"!invited:s\"}");
        }

        @Override
        public CompletionStage<String> put(String p, String t, String b) {
            throw new UnsupportedOperationException();
        }
    }

    private static DefaultMatrixClient client(FakeServer server, boolean autoJoin) {
        return new DefaultMatrixClient(server, "tok", "@bot:s", new InMemorySyncTokenStore(),
            ev -> {}, new MatrixClientOptions(0, 30_000, autoJoin));
    }

    @Test
    void autoJoinEnabled_joinsInvitedRoom() throws Exception {
        FakeServer server = new FakeServer();
        DefaultMatrixClient client = client(server, true);

        client.start().toCompletableFuture().join();
        try {
            assertTrue(server.joined.await(5, TimeUnit.SECONDS), "join attendu");
        } finally {
            client.stop().toCompletableFuture().join();
        }

        assertEquals(List.of("/_matrix/client/v3/rooms/%21invited%3As/join"), server.posts);
    }

    @Test
    void autoJoinDisabled_doesNotJoin() throws Exception {
        FakeServer server = new FakeServer();
        DefaultMatrixClient client = client(server, false);

        client.start().toCompletableFuture().join();
        try {
            assertTrue(server.syncs.await(5, TimeUnit.SECONDS));
        } finally {
            client.stop().toCompletableFuture().join();
        }

        assertTrue(server.posts.isEmpty());
    }

    @Test
    void options_rejectNonPositiveTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new MatrixClientOptions(0, 0, false));
    }
}
