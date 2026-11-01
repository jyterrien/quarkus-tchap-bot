package fr.gouv.tchap.matrix.core.client;

import fr.gouv.tchap.matrix.auth.Session;
import fr.gouv.tchap.matrix.core.auth.DefaultSession;
import fr.gouv.tchap.matrix.core.sync.InMemorySyncTokenStore;
import fr.gouv.tchap.matrix.core.transport.RecordingHttpTransport;
import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.room.MatrixRoom;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DefaultMatrixClientRoomsTest {

    private final RecordingHttpTransport transport = new RecordingHttpTransport();
    private final DefaultMatrixClient client = new DefaultMatrixClient(
        transport, "tok", null, new InMemorySyncTokenStore(), ev -> {});

    @Test
    void verifySession_learnsTheUserIdFromTheServer() {
        transport.response = "{\"user_id\":\"@app:tchap.gouv.fr\",\"device_id\":\"DEV1\",\"is_guest\":false}";

        Session verified = client.verifySession().toCompletableFuture().join();

        DefaultSession session = assertInstanceOf(DefaultSession.class, verified);
        assertEquals("@app:tchap.gouv.fr", session.userId());
        assertEquals("DEV1", session.deviceId());
        assertEquals("tok", session.accessToken());
        assertSame(verified, client.session());
        assertEquals("/_matrix/client/v3/account/whoami", transport.path);
    }

    @Test
    void verifySession_failsWhenUserIdIsMissing() {
        transport.response = "{}";

        CompletionException ex = assertThrows(CompletionException.class,
            () -> client.verifySession().toCompletableFuture().join());

        assertInstanceOf(MatrixException.class, ex.getCause());
    }

    @Test
    void room_isBoundToItsRoomId() {
        transport.response = "{\"event_id\":\"$e1\"}";

        MatrixRoom room = client.room("!supervisors:s");
        room.sendText("hello").toCompletableFuture().join();

        assertEquals("!supervisors:s", room.roomId());
        assertTrue(transport.path.contains("/rooms/%21supervisors%3As/send/"), transport.path);
    }

    @Test
    void room_blankId_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> client.room(" "));
    }

    @Test
    void restrictToRooms_isAppliedToSyncRequests() throws Exception {
        List<String> paths = new CopyOnWriteArrayList<>();
        CountDownLatch firstSync = new CountDownLatch(1);
        HttpTransport fake = new HttpTransport() {
            @Override
            public CompletionStage<String> get(String path, String accessToken) {
                paths.add(path);
                firstSync.countDown();
                return CompletableFuture.completedFuture("{\"next_batch\":\"s1\"}");
            }

            @Override
            public CompletionStage<String> put(String p, String t, String b) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CompletionStage<String> post(String p, String t, String b) {
                throw new UnsupportedOperationException();
            }
        };
        DefaultMatrixClient c = new DefaultMatrixClient(fake, "tok", null,
            new InMemorySyncTokenStore(), ev -> {});
        c.restrictToRooms(List.of("!a:s", "!b:s"));

        c.start().toCompletableFuture().join();
        try {
            assertTrue(firstSync.await(5, TimeUnit.SECONDS));
        } finally {
            c.stop().toCompletableFuture().join();
        }

        String path = URLDecoder.decode(paths.get(0), StandardCharsets.UTF_8);
        assertTrue(path.contains("\"rooms\":[\"!a:s\",\"!b:s\"]"), path);
    }
}
