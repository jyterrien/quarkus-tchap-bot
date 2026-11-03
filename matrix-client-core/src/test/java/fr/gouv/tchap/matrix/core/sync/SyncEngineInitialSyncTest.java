package fr.gouv.tchap.matrix.core.sync;

import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static fr.gouv.tchap.matrix.core.HttpTransportMocks.awaitGets;
import static fr.gouv.tchap.matrix.core.HttpTransportMocks.getPaths;
import static fr.gouv.tchap.matrix.core.HttpTransportMocks.scriptedGet;
import static org.junit.jupiter.api.Assertions.*;

/** Vérifie que le filtre « pas d'historique » n'est appliqué qu'au premier /sync. */
class SyncEngineInitialSyncTest {

    /** Réponses /sync successives : next_batch s1 puis s2 (répété). */
    private static HttpTransport syncTransport() {
        return scriptedGet("{\"next_batch\":\"s1\"}", "{\"next_batch\":\"s2\"}");
    }

    @Test
    void firstSyncUsesFilter_nextSyncUsesSinceWithoutFilter() {
        HttpTransport transport = syncTransport();
        SyncEngine engine = new SyncEngine(
            new MatrixHttpApi(transport, "tok"),
            new InMemorySyncTokenStore(),
            ev -> {},
            0);

        engine.start().toCompletableFuture().join();
        try {
            awaitGets(transport, 2);
        } finally {
            engine.stop().toCompletableFuture().join();
        }

        List<String> paths = getPaths(transport);
        String first = paths.get(0);
        String second = paths.get(1);
        assertTrue(first.contains("filter="), first);
        assertFalse(first.contains("since="), first);
        assertTrue(second.contains("since=s1"), second);
        assertFalse(second.contains("filter="), second);
    }

    @Test
    void existingTokenFromStore_skipsFilter() {
        HttpTransport transport = syncTransport();
        InMemorySyncTokenStore store = new InMemorySyncTokenStore();
        store.save("stored").toCompletableFuture().join();
        SyncEngine engine = new SyncEngine(
            new MatrixHttpApi(transport, "tok"), store, ev -> {}, 0);

        engine.start().toCompletableFuture().join();
        try {
            awaitGets(transport, 2);
        } finally {
            engine.stop().toCompletableFuture().join();
        }

        String first = getPaths(transport).get(0);
        assertTrue(first.contains("since=stored"));
        assertFalse(first.contains("filter="));
    }
}
