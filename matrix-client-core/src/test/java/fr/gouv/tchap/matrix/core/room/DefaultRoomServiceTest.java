package fr.gouv.tchap.matrix.core.room;

import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.core.transport.RecordingHttpTransport;
import fr.gouv.tchap.matrix.exception.MatrixException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class DefaultRoomServiceTest {

    private final RecordingHttpTransport transport = new RecordingHttpTransport();
    private final DefaultRoomService service = new DefaultRoomService(new MatrixHttpApi(transport, "tok"));

    @Test
    void resolveAndJoin_returnsTheResolvedRoomId() {
        transport.response = "{\"room_id\":\"!abc:s\"}";

        String roomId = service.resolveAndJoin("#alias:s").toCompletableFuture().join();

        assertEquals("!abc:s", roomId);
        assertEquals("POST", transport.method);
        assertEquals("/_matrix/client/v3/join/%23alias%3As", transport.path);
    }

    @Test
    void resolveAndJoin_failsWhenResponseHasNoRoomId() {
        transport.response = "{}";

        CompletionException ex = assertThrows(CompletionException.class,
            () -> service.resolveAndJoin("#alias:s").toCompletableFuture().join());

        assertInstanceOf(MatrixException.class, ex.getCause());
    }

    @Test
    void join_byRoomId_stillPostsToRoomsEndpoint() {
        service.join("!abc:s").toCompletableFuture().join();

        assertEquals("POST", transport.method);
        assertEquals("/_matrix/client/v3/rooms/%21abc%3As/join", transport.path);
    }
}
