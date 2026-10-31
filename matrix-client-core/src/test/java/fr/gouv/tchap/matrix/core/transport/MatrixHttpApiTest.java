package fr.gouv.tchap.matrix.core.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MatrixHttpApiTest {

    private final RecordingHttpTransport transport = new RecordingHttpTransport();
    private final MatrixHttpApi api = new MatrixHttpApi(transport, "tok");

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
    void sync_usesGetWithSinceAndTimeout() {
        api.sync("s 1", 5000).toCompletableFuture().join();

        assertEquals("GET", transport.method);
        assertEquals("/_matrix/client/v3/sync?timeout=5000&since=s+1", transport.path);
    }
}
