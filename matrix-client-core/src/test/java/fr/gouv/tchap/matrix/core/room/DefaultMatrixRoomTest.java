package fr.gouv.tchap.matrix.core.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.gouv.tchap.matrix.core.message.DefaultMessageService;
import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.core.transport.RecordingHttpTransport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DefaultMatrixRoomTest {

    private final RecordingHttpTransport transport = new RecordingHttpTransport();
    private final DefaultMatrixRoom room = new DefaultMatrixRoom("!oncall:s",
        new DefaultMessageService(new MatrixHttpApi(transport, "tok"), () -> "txn1"));

    @Test
    void sendText_goesToTheBoundRoomOnly() throws Exception {
        transport.response = "{\"event_id\":\"$e1\"}";

        String eventId = room.sendText("Base indisponible").toCompletableFuture().join().eventId();

        assertEquals("$e1", eventId);
        assertEquals("PUT", transport.method);
        assertEquals("/_matrix/client/v3/rooms/%21oncall%3As/send/m.room.message/txn1", transport.path);
        assertEquals("m.text", new ObjectMapper().readTree(transport.body).path("msgtype").asText());
    }

    @Test
    void sendNotice_usesNoticeMsgtype() throws Exception {
        transport.response = "{\"event_id\":\"$e2\"}";

        room.sendNotice("Alerte").toCompletableFuture().join();

        var sent = new ObjectMapper().readTree(transport.body);
        assertEquals("m.notice", sent.path("msgtype").asText());
        assertEquals("Alerte", sent.path("body").asText());
    }

    @Test
    void exposesItsRoomId() {
        assertEquals("!oncall:s", room.roomId());
    }

    @Test
    void blankRoomId_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DefaultMatrixRoom(" ", null));
        assertThrows(IllegalArgumentException.class, () -> new DefaultMatrixRoom(null, null));
    }
}
