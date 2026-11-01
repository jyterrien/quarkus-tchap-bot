package fr.gouv.tchap.matrix.core.sync;

import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Filtre de salons : envoyé à chaque /sync, appliqué aussi côté client, modifiable à chaud. */
class SyncEngineRoomFilterTest {

    private static String joined(String roomId, String... eventIds) {
        StringBuilder events = new StringBuilder();
        for (String id : eventIds) {
            if (events.length() > 0) events.append(',');
            events.append("{\"event_id\":\"").append(id).append("\",\"sender\":\"@u:s\",")
                .append("\"type\":\"m.room.message\",\"origin_server_ts\":1,\"content\":{}}");
        }
        return "\"" + roomId + "\":{\"timeline\":{\"events\":[" + events + "]}}";
    }

    private static String response(String nextBatch, String joinedRooms, String invitedRooms) {
        return "{\"next_batch\":\"" + nextBatch + "\",\"rooms\":{\"join\":{" + joinedRooms + "},"
            + "\"invite\":{" + invitedRooms + "}}}";
    }

    private static String decoded(String path) {
        return URLDecoder.decode(path, StandardCharsets.UTF_8);
    }

    static class Fake implements HttpTransport {
        final List<String> paths = new CopyOnWriteArrayList<>();
        final CountDownLatch calls;
        volatile Runnable onFirstCall = () -> {};
        private final String[] responses;
        private final AtomicInteger n = new AtomicInteger();

        Fake(int callsToWait, String... responses) {
            this.calls = new CountDownLatch(callsToWait);
            this.responses = responses;
        }

        @Override
        public CompletionStage<String> get(String path, String accessToken) {
            int i = n.getAndIncrement();
            paths.add(path);
            if (i == 0) onFirstCall.run();
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

    private static void runUntilCalls(SyncEngine engine, Fake fake) throws Exception {
        engine.start().toCompletableFuture().join();
        try {
            assertTrue(fake.calls.await(5, TimeUnit.SECONDS), "nombre d'appels /sync non atteint");
        } finally {
            engine.stop().toCompletableFuture().join();
        }
    }

    private static SyncEngine engine(Fake fake, java.util.function.Consumer<fr.gouv.tchap.matrix.event.MatrixEvent> events,
                                     java.util.function.Consumer<String> invites) {
        return new SyncEngine(new MatrixHttpApi(fake, "tok"), new InMemorySyncTokenStore(),
            events, invites, 0, 30_000);
    }

    @Test
    void roomFilter_isSentOnEveryRequest_historyLimitOnlyOnTheFirst() throws Exception {
        Fake fake = new Fake(2, response("s1", "", ""));
        SyncEngine engine = engine(fake, ev -> {}, null);
        engine.setRoomFilter(List.of("!a:s"));

        runUntilCalls(engine, fake);

        assertTrue(decoded(fake.paths.get(0)).contains("{\"room\":{\"rooms\":[\"!a:s\"],\"timeline\":{\"limit\":0}}}"),
            decoded(fake.paths.get(0)));
        assertTrue(decoded(fake.paths.get(1)).contains("since=s1"));
        assertTrue(decoded(fake.paths.get(1)).contains("{\"room\":{\"rooms\":[\"!a:s\"]}}"),
            decoded(fake.paths.get(1)));
    }

    @Test
    void eventsFromOtherRooms_areDroppedEvenIfTheServerSendsThem() throws Exception {
        String both = joined("!a:s", "$e1") + "," + joined("!b:s", "$e2");
        Fake fake = new Fake(2, response("s1", both, ""));
        List<String> delivered = new CopyOnWriteArrayList<>();
        SyncEngine engine = engine(fake, ev -> delivered.add(ev.eventId()), null);
        engine.setRoomFilter(Set.of("!a:s"));

        runUntilCalls(engine, fake);

        assertEquals(List.of("$e1"), delivered);
    }

    @Test
    void withoutFilter_allRoomsAreDelivered() throws Exception {
        String both = joined("!a:s", "$e1") + "," + joined("!b:s", "$e2");
        Fake fake = new Fake(2, response("s1", both, ""));
        List<String> delivered = new CopyOnWriteArrayList<>();

        runUntilCalls(engine(fake, ev -> delivered.add(ev.eventId()), null), fake);

        assertEquals(List.of("$e1", "$e2"), delivered);
    }

    @Test
    void invitationsToOtherRooms_areIgnored() throws Exception {
        String invites = "\"!a:s\":{},\"!other:s\":{}";
        Fake fake = new Fake(2, response("s1", "", invites));
        List<String> invited = new CopyOnWriteArrayList<>();
        SyncEngine engine = engine(fake, ev -> {}, invited::add);
        engine.setRoomFilter(Set.of("!a:s"));

        runUntilCalls(engine, fake);

        assertEquals(List.of("!a:s"), invited);
    }

    @Test
    void roomFilter_canBeChangedWhileRunning_effectiveOnNextSync() throws Exception {
        Fake fake = new Fake(2, response("s1", "", ""));
        AtomicReference<SyncEngine> ref = new AtomicReference<>();
        SyncEngine engine = engine(fake, ev -> {}, null);
        ref.set(engine);
        engine.setRoomFilter(Set.of("!a:s"));
        fake.onFirstCall = () -> ref.get().setRoomFilter(Set.of("!b:s"));

        runUntilCalls(engine, fake);

        assertTrue(decoded(fake.paths.get(1)).contains("[\"!b:s\"]"), decoded(fake.paths.get(1)));
        assertFalse(decoded(fake.paths.get(1)).contains("!a:s"));
    }
}
