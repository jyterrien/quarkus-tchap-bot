package fr.gouv.tchap.matrix.core.sync;

import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.event.MatrixEvent;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final String WITH_INVITE_AND_EVENT =
        "{\"next_batch\":\"s1\",\"rooms\":{"
            + "\"invite\":{\"!invited:s\":{\"invite_state\":{\"events\":[]}}},"
            + "\"join\":{\"!r:s\":{\"timeline\":{\"events\":[{\"event_id\":\"$e1\",\"sender\":\"@u:s\","
            + "\"type\":\"m.room.message\",\"origin_server_ts\":1,\"content\":{}}]}}}}}";

    private static void run(HttpTransport transport, int callsToWait,
                            Consumer<String> inviteConsumer, long timeoutMs) {
        run(transport, callsToWait, ev -> {}, inviteConsumer, timeoutMs);
    }

    private static void run(HttpTransport transport, int callsToWait, Consumer<MatrixEvent> eventConsumer,
                            Consumer<String> inviteConsumer, long timeoutMs) {
        SyncEngine engine = new SyncEngine(new MatrixHttpApi(transport, "tok"),
            new InMemorySyncTokenStore(), eventConsumer, inviteConsumer, 0, timeoutMs);
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
        AtomicInteger invoked = new AtomicInteger();
        HttpTransport transport = scriptedGet(WITH_INVITE, PLAIN);

        // 3 GET attendus : le 3e n'a lieu que si la boucle a survécu à l'exception du consumer
        run(transport, 3, roomId -> {
            invoked.incrementAndGet();
            throw new IllegalStateException("boom");
        }, 30_000);

        assertEquals(1, invoked.get());
    }

    @Test
    void withoutInviteConsumer_invitationsAreSkipped_andEventsAreStillDelivered() {
        List<String> delivered = new CopyOnWriteArrayList<>();

        run(scriptedGet(WITH_INVITE_AND_EVENT, PLAIN), 2, ev -> delivered.add(ev.eventId()), null, 30_000);

        assertEquals(List.of("$e1"), delivered);
    }

    @Test
    void nonPositiveTimeout_isRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            new SyncEngine(null, new InMemorySyncTokenStore(), ev -> {}, null, 0, 0));
    }
}
