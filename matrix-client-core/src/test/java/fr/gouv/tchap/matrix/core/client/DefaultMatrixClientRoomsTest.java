package fr.gouv.tchap.matrix.core.client;

import fr.gouv.tchap.matrix.auth.Session;
import fr.gouv.tchap.matrix.core.auth.DefaultSession;
import fr.gouv.tchap.matrix.core.sync.InMemorySyncTokenStore;
import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.room.MatrixRoom;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static fr.gouv.tchap.matrix.core.HttpTransportMocks.answeringEmptyJson;
import static fr.gouv.tchap.matrix.core.HttpTransportMocks.awaitGets;
import static fr.gouv.tchap.matrix.core.HttpTransportMocks.getPaths;
import static fr.gouv.tchap.matrix.core.HttpTransportMocks.scriptedGet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

class DefaultMatrixClientRoomsTest {

    private final HttpTransport transport = answeringEmptyJson();
    private final DefaultMatrixClient client = new DefaultMatrixClient(
        transport, "tok", null, new InMemorySyncTokenStore(), ev -> {});

    @Test
    void verifySession_learnsTheUserIdFromTheServer() {
        doReturn(CompletableFuture.completedFuture(
            "{\"user_id\":\"@app:tchap.gouv.fr\",\"device_id\":\"DEV1\",\"is_guest\":false}"))
            .when(transport).get(any(), any());

        Session verified = client.verifySession().toCompletableFuture().join();

        DefaultSession session = assertInstanceOf(DefaultSession.class, verified);
        assertEquals("@app:tchap.gouv.fr", session.userId());
        assertEquals("DEV1", session.deviceId());
        assertEquals("tok", session.accessToken());
        assertSame(verified, client.session());
        verify(transport).get("/_matrix/client/v3/account/whoami", "tok");
    }

    @Test
    void verifySession_failsWhenUserIdIsMissing() {
        // le transport répond « {} » par défaut

        CompletionException ex = assertThrows(CompletionException.class,
            () -> client.verifySession().toCompletableFuture().join());

        assertInstanceOf(MatrixException.class, ex.getCause());
    }

    @Test
    void room_isBoundToItsRoomId() {
        doReturn(CompletableFuture.completedFuture("{\"event_id\":\"$e1\"}"))
            .when(transport).put(any(), any(), any());

        MatrixRoom room = client.room("!supervisors:s");
        room.sendText("hello").toCompletableFuture().join();

        assertEquals("!supervisors:s", room.roomId());
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(transport).put(path.capture(), eq("tok"), any());
        assertTrue(path.getValue().contains("/rooms/%21supervisors%3As/send/"), path.getValue());
    }

    @Test
    void room_blankId_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> client.room(" "));
    }

    @Test
    void restrictToRooms_isAppliedToSyncRequests() {
        HttpTransport fake = scriptedGet("{\"next_batch\":\"s1\"}");
        DefaultMatrixClient c = new DefaultMatrixClient(fake, "tok", null,
            new InMemorySyncTokenStore(), ev -> {});
        c.restrictToRooms(List.of("!a:s", "!b:s"));

        c.start().toCompletableFuture().join();
        try {
            awaitGets(fake, 1);
        } finally {
            c.stop().toCompletableFuture().join();
        }

        String path = URLDecoder.decode(getPaths(fake).get(0), StandardCharsets.UTF_8);
        assertTrue(path.contains("\"rooms\":[\"!a:s\",\"!b:s\"]"), path);
    }
}
