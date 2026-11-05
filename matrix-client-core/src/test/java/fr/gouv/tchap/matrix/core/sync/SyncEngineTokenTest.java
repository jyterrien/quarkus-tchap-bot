package fr.gouv.tchap.matrix.core.sync;

import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.sync.SyncTokenStore;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static fr.gouv.tchap.matrix.core.HttpTransportMocks.awaitGets;
import static fr.gouv.tchap.matrix.core.HttpTransportMocks.getPaths;
import static fr.gouv.tchap.matrix.core.HttpTransportMocks.scriptedGet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/** Persistance du token de synchronisation (next_batch) par le moteur. */
class SyncEngineTokenTest {

    private static String withEvent(String nextBatch, String eventId) {
        return "{\"next_batch\":\"" + nextBatch + "\",\"rooms\":{\"join\":{\"!r:s\":{\"timeline\":{\"events\":["
            + "{\"event_id\":\"" + eventId + "\",\"sender\":\"@u:s\",\"type\":\"m.room.message\","
            + "\"origin_server_ts\":1,\"content\":{}}]}}}}}";
    }

    /** Store vide dont load() réussit ; save() est à configurer par le test. */
    private static SyncTokenStore emptyStore() {
        SyncTokenStore store = mock(SyncTokenStore.class);
        doReturn(CompletableFuture.completedFuture(Optional.empty())).when(store).load();
        return store;
    }

    private static void run(SyncTokenStore store, Consumer<fr.gouv.tchap.matrix.event.MatrixEvent> events,
                            HttpTransport transport, int gets) {
        SyncEngine engine = new SyncEngine(new MatrixHttpApi(transport, "tok"), store, events);
        engine.start().toCompletableFuture().join();
        try {
            awaitGets(transport, gets);
        } finally {
            engine.stop().toCompletableFuture().join();
        }
    }

    @Test
    void token_isSavedOnlyAfterTheEventsOfTheResponseAreDelivered() {
        List<String> trace = new CopyOnWriteArrayList<>();
        SyncTokenStore store = emptyStore();
        doAnswer(invocation -> {
            trace.add("save:" + invocation.getArgument(0));
            return CompletableFuture.completedFuture(null);
        }).when(store).save(any());
        HttpTransport transport = scriptedGet(withEvent("s1", "$e1"), "{\"next_batch\":\"s2\"}");

        run(store, ev -> trace.add("event:" + ev.eventId()), transport, 2);

        // at-least-once : si l'application s'arrête pendant la livraison, le token n'est pas encore avancé
        assertEquals(List.of("event:$e1", "save:s1"), List.copyOf(trace).subList(0, 2));
    }

    @Test
    void aFailingTokenSave_doesNotStopTheLoop_andTheNextRequestStillAdvances() {
        SyncTokenStore store = emptyStore();
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("disk full")))
            .doReturn(CompletableFuture.completedFuture(null))
            .when(store).save(any());
        HttpTransport transport = scriptedGet("{\"next_batch\":\"s1\"}", "{\"next_batch\":\"s2\"}");

        run(store, ev -> {}, transport, 3);

        List<String> paths = getPaths(transport);
        assertTrue(paths.get(1).contains("since=s1"), paths.get(1));
        assertTrue(paths.get(2).contains("since=s2"), paths.get(2));
    }

    @Test
    void responseWithoutNextBatch_doesNotAdvanceTheToken() {
        SyncTokenStore store = emptyStore();
        doReturn(CompletableFuture.completedFuture(null)).when(store).save(any());
        HttpTransport transport = scriptedGet("{}", "{\"next_batch\":\"s1\"}");

        run(store, ev -> {}, transport, 2);

        String secondRequest = getPaths(transport).get(1);
        assertFalse(secondRequest.contains("since="), secondRequest);
    }
}
