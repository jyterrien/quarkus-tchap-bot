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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Timeout de sync configurable et gestion des invitations. */
class SyncEngineOptionsTest {

    private static final String WITH_INVITE =
        "{\"next_batch\":\"s1\",\"rooms\":{\"invite\":{\"!invited:s\":{\"invite_state\":{\"events\":[]}}}}}";
    private static final String PLAIN = "{\"next_batch\":\"s2\"}";

    /** Répond avec les réponses du script, dans l'ordre (la dernière se répète). */
    static class ScriptedGet implements HttpTransport {
        final List<String> paths = new CopyOnWriteArrayList<>();
        final CountDownLatch calls;
        private final String[] responses;
        private final AtomicInteger n = new AtomicInteger();

        ScriptedGet(int callsToWait, String... responses) {
            this.calls = new CountDownLatch(callsToWait);
            this.responses = responses;
        }

        @Override
        public CompletionStage<String> get(String path, String accessToken) {
            int i = n.getAndIncrement();
            paths.add(path);
            calls.countDown();
            return CompletableFuture.completedFuture(responses[Math.min(i, responses.length - 1)]);
        }

        @Override
        public CompletionStage<String> put(String p, String t, String b) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<String> post(String p, String t, String b) {
            throw new UnsupportedOperationException();
        }
    }

    private static void run(ScriptedGet transport, Consumer<String> inviteConsumer, long timeoutMs)
        throws Exception {
        SyncEngine engine = new SyncEngine(new MatrixHttpApi(transport, "tok"),
            new InMemorySyncTokenStore(), ev -> {}, inviteConsumer, 0, timeoutMs);
        engine.start().toCompletableFuture().join();
        try {
            assertTrue(transport.calls.await(5, TimeUnit.SECONDS), "nombre d'appels /sync non atteint");
        } finally {
            engine.stop().toCompletableFuture().join();
        }
    }

    @Test
    void configuredTimeout_isUsedInSyncRequests() throws Exception {
        ScriptedGet transport = new ScriptedGet(1, PLAIN);

        run(transport, null, 1234);

        assertTrue(transport.paths.get(0).contains("timeout=1234"), transport.paths.get(0));
    }

    @Test
    void invitedRooms_areHandedToTheInviteConsumer() throws Exception {
        List<String> invited = new CopyOnWriteArrayList<>();

        run(new ScriptedGet(2, WITH_INVITE, PLAIN), invited::add, 30_000);

        assertEquals(List.of("!invited:s"), invited);
    }

    @Test
    void inviteConsumerException_doesNotStopTheLoop() throws Exception {
        ScriptedGet transport = new ScriptedGet(3, WITH_INVITE, PLAIN);

        run(transport, roomId -> { throw new IllegalStateException("boom"); }, 30_000);

        assertTrue(transport.paths.size() >= 3);
    }

    @Test
    void invitationsAreIgnored_whenNoInviteConsumer() {
        ScriptedGet transport = new ScriptedGet(2, WITH_INVITE, PLAIN);

        assertDoesNotThrow(() -> run(transport, null, 30_000));
    }

    @Test
    void nonPositiveTimeout_isRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            new SyncEngine(null, new InMemorySyncTokenStore(), ev -> {}, null, 0, 0));
    }
}
