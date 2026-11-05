package fr.gouv.tchap.matrix.core.room;

import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static fr.gouv.tchap.matrix.core.HttpTransportMocks.answeringEmptyJson;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class DefaultRoomServiceTest {

    private final HttpTransport transport = answeringEmptyJson();
    private final DefaultRoomService service = new DefaultRoomService(new MatrixHttpApi(transport, "tok"));

    @Test
    void resolveAndJoin_returnsTheResolvedRoomId() {
        doReturn(CompletableFuture.completedFuture("{\"room_id\":\"!abc:s\"}"))
            .when(transport).post(any(), any(), any());

        String roomId = service.resolveAndJoin("#alias:s").toCompletableFuture().join();

        assertEquals("!abc:s", roomId);
        verify(transport).post("/_matrix/client/v3/join/%23alias%3As", "tok", "{}");
        verifyNoMoreInteractions(transport);
    }

    @Test
    void resolveAndJoin_failsWhenResponseHasNoRoomId() {
        // le transport répond « {} » par défaut

        CompletionException ex = assertThrows(CompletionException.class,
            () -> service.resolveAndJoin("#alias:s").toCompletableFuture().join());

        assertInstanceOf(MatrixException.class, ex.getCause());
    }

    @Test
    void join_byRoomId_stillPostsToRoomsEndpoint() {
        service.join("!abc:s").toCompletableFuture().join();

        verify(transport).post("/_matrix/client/v3/rooms/%21abc%3As/join", "tok", "{}");
        verifyNoMoreInteractions(transport);
    }

    @Test
    void resolveAndJoin_failsWhenResponseIsNotJson() {
        doReturn(CompletableFuture.completedFuture("<html>oops</html>"))
            .when(transport).post(any(), any(), any());

        CompletionException ex = assertThrows(CompletionException.class,
            () -> service.resolveAndJoin("#alias:s").toCompletableFuture().join());

        assertInstanceOf(MatrixException.class, ex.getCause());
    }

    @Test
    void leave_postsToTheLeaveEndpoint() {
        service.leave("!abc:s").toCompletableFuture().join();

        verify(transport).post("/_matrix/client/v3/rooms/%21abc%3As/leave", "tok", "{}");
        verifyNoMoreInteractions(transport);
    }
}
