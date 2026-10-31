package fr.gouv.tchap.matrix.core.transport;

import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MatrixHttpApiTest {

    private final RecordingHttpTransport transport = new RecordingHttpTransport();
    private final MatrixHttpApi api = new MatrixHttpApi(transport, "tok");

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Test
    void joinRoom_usesPost() {
        api.joinRoom("!room:server").toCompletableFuture().join();

        assertEquals("POST", transport.method);
        assertEquals("/_matrix/client/v3/rooms/%21room%3Aserver/join", transport.path);
        assertEquals("{}", transport.body);
        assertEquals("tok", transport.token);
    }

    @Test
    void leaveRoom_usesPost() {
        api.leaveRoom("!room:server").toCompletableFuture().join();

        assertEquals("POST", transport.method);
        assertEquals("/_matrix/client/v3/rooms/%21room%3Aserver/leave", transport.path);
        assertEquals("{}", transport.body);
    }

    @Test
    void sendMessage_usesPut() {
        api.sendMessage("!room:server", "txn1", "{\"a\":1}").toCompletableFuture().join();

        assertEquals("PUT", transport.method);
        assertEquals("/_matrix/client/v3/rooms/%21room%3Aserver/send/m.room.message/txn1",
            transport.path);
        assertEquals("{\"a\":1}", transport.body);
    }

    @Test
    void sync_incremental_usesSinceAndNoFilter() {
        api.sync("s 1", 5000, 0).toCompletableFuture().join();

        assertEquals("GET", transport.method);
        assertEquals("/_matrix/client/v3/sync?timeout=5000&since=s+1", transport.path);
    }

    @Test
    void sync_initial_withLimitZero_addsFilterWithoutHistory() {
        api.sync(null, 30000, 0).toCompletableFuture().join();

        String expectedFilter = enc("{\"room\":{\"timeline\":{\"limit\":0}}}");
        assertEquals("/_matrix/client/v3/sync?timeout=30000&filter=" + expectedFilter,
            transport.path);
    }

    @Test
    void sync_initial_withLimit20_addsFilterWithThatLimit() {
        api.sync("", 30000, 20).toCompletableFuture().join();

        assertTrue(transport.path.contains("&filter=" + enc("{\"room\":{\"timeline\":{\"limit\":20}}}")));
    }

    @Test
    void sync_initial_withNegativeLimit_hasNoFilter() {
        api.sync(null, 30000, -1).toCompletableFuture().join();

        assertEquals("/_matrix/client/v3/sync?timeout=30000", transport.path);
    }

    @Test
    void sync_twoArgOverload_hasNoFilter() {
        api.sync(null, 1000).toCompletableFuture().join();

        assertFalse(transport.path.contains("filter="));
    }
}
