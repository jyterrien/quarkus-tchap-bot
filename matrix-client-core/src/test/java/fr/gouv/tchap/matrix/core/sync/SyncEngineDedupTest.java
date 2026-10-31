package fr.gouv.tchap.matrix.core.sync;

import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.event.MatrixEvent;
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

class SyncEngineDedupTest {

    /** Répond à chaque /sync avec les event_id du script (le dernier élément se répète). */
    static class ScriptedTransport implements HttpTransport {
        private final List<String[]> script;
        final CountDownLatch calls;
        private final AtomicInteger n = new AtomicInteger();

        ScriptedTransport(int callsToWait, String[]... script) {
            this.script = List.of(script);
            this.calls = new CountDownLatch(callsToWait);
        }

        @Override
        public CompletionStage<String> get(String path, String accessToken) {
            int i = n.getAndIncrement();
            String[] ids = script.get(Math.min(i, script.size() - 1));
            calls.countDown();
            return CompletableFuture.completedFuture(response(i + 1, ids));
        }

        @Override
        public CompletionStage<String> put(String p, String t, String b) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<String> post(String p, String t, String b) {
            throw new UnsupportedOperationException();
        }

        private static String response(int batch, String[] ids) {
            StringBuilder events = new StringBuilder();
            for (String id : ids) {
                if (events.length() > 0) events.append(',');
                events.append("{\"event_id\":\"").append(id).append("\",")
                    .append("\"sender\":\"@u:s\",\"type\":\"m.room.message\",")
                    .append("\"origin_server_ts\":1,\"content\":{\"body\":\"x\"}}");
            }
            return "{\"next_batch\":\"s" + batch + "\",\"rooms\":{\"join\":{\"!r:s\":"
                + "{\"timeline\":{\"events\":[" + events + "]}}}}}";
        }
    }

    private void run(ScriptedTransport transport, int dedupCapacity, Consumer<MatrixEvent> consumer)
        throws Exception {
        SyncEngine engine = new SyncEngine(
            new MatrixHttpApi(transport, "tok"),
            new InMemorySyncTokenStore(),
            consumer, 0, dedupCapacity);
        engine.start().toCompletableFuture().join();
        try {
            assertTrue(transport.calls.await(5, TimeUnit.SECONDS), "nombre d'appels /sync non atteint");
        } finally {
            engine.stop().toCompletableFuture().join();
        }
    }

    @Test
    void sameEventInSeveralSyncs_isDeliveredOnce() throws Exception {
        List<String> delivered = new CopyOnWriteArrayList<>();

        run(new ScriptedTransport(3, new String[]{"$e1"}), 100,
            ev -> delivered.add(ev.eventId()));

        assertEquals(List.of("$e1"), delivered);
    }

    @Test
    void dedupWindowIsBounded_oldestIdsAreForgotten() throws Exception {
        List<String> delivered = new CopyOnWriteArrayList<>();
        // capacité 2 : après e1, e2, e3 → e1 est évincé et sera redélivré
        ScriptedTransport transport = new ScriptedTransport(5,
            new String[]{"$e1"}, new String[]{"$e2"}, new String[]{"$e3"}, new String[]{"$e1"});

        run(transport, 2, ev -> delivered.add(ev.eventId()));

        assertEquals(List.of("$e1", "$e2", "$e3", "$e1"), delivered);
    }

    @Test
    void consumerException_doesNotStopTheLoopNorOtherEvents() throws Exception {
        List<String> delivered = new CopyOnWriteArrayList<>();
        ScriptedTransport transport = new ScriptedTransport(3,
            new String[]{"$e1", "$e2"}, new String[]{"$e3"});

        run(transport, 100, ev -> {
            if (ev.eventId().equals("$e1")) throw new IllegalStateException("boom");
            delivered.add(ev.eventId());
        });

        assertEquals(List.of("$e2", "$e3"), delivered);
    }

    @Test
    void invalidCapacity_isRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            new SyncEngine(null, new InMemorySyncTokenStore(), ev -> {}, 0, 0));
    }
}
