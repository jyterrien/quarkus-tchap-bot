package fr.gouv.tchap.matrix.core.sync;

import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static fr.gouv.tchap.matrix.core.HttpTransportMocks.awaitGets;
import static fr.gouv.tchap.matrix.core.HttpTransportMocks.getPaths;
import static fr.gouv.tchap.matrix.core.HttpTransportMocks.scriptedGet;
import static org.junit.jupiter.api.Assertions.*;

/** Timeout de sync configurable et gestion des invitations. */
class SyncEngineOptionsTest {

    private static final String WITH_INVITE =
        "{\"next_batch\":\"s1\",\"rooms\":{\"invite\":{\"!invited:s\":{\"invite_state\":{\"events\":[]}}}}}";
    private static final String PLAIN = "{\"next_batch\":\"s2\"}";

    private static void run(HttpTransport transport, int callsToWait,
                            Consumer<String> inviteConsumer, long timeoutMs) {
        SyncEngine engine = new SyncEngine(new MatrixHttpApi(transport, "tok"),
            new InMemorySyncTokenStore(), ev -> {}, inviteConsumer, 0, timeoutMs);
        engine.start().toCompletableFuture().join();
        try {
            awaitGets(transport, callsToWait);
        } finally {
            engine.stop().toCompletableFuture().join();
        }
    }

    @Test
    void configuredTimeout_isUsedInSyncRequests() {
        HttpTransport transport = scriptedGet(PLAIN);

        run(transport, 1, null, 1234);

        String first = getPaths(transport).get(0);
        assertTrue(first.contains("timeout=1234"), first);
    }

    @Test
    void invitedRooms_areHandedToTheInviteConsumer() {
        List<String> invited = new CopyOnWriteArrayList<>();

        run(scriptedGet(WITH_INVITE, PLAIN), 2, invited::add, 30_000);

        assertEquals(List.of("!invited:s"), invited);
    }

    @Test
    void inviteConsumerException_doesNotStopTheLoop() {
        HttpTransport transport = scriptedGet(WITH_INVITE, PLAIN);

        run(transport, 3, roomId -> { throw new IllegalStateException("boom"); }, 30_000);

        assertTrue(getPaths(transport).size() >= 3);
    }

    @Test
    void invitationsAreIgnored_whenNoInviteConsumer() {
        HttpTransport transport = scriptedGet(WITH_INVITE, PLAIN);

        assertDoesNotThrow(() -> run(transport, 2, null, 30_000));
    }

    @Test
    void nonPositiveTimeout_isRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            new SyncEngine(null, new InMemorySyncTokenStore(), ev -> {}, null, 0, 0));
    }
}
