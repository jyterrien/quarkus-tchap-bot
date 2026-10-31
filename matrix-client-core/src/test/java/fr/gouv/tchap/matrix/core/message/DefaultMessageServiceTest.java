package fr.gouv.tchap.matrix.core.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.core.transport.RecordingHttpTransport;
import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.message.MessageReceipt;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class DefaultMessageServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RecordingHttpTransport transport = new RecordingHttpTransport();
    private final DefaultMessageService service =
        new DefaultMessageService(new MatrixHttpApi(transport, "tok"), () -> "txn42");

    @Test
    void sendText_putsToCorrectPathWithTxnId() {
        transport.response = "{\"event_id\":\"$abc\"}";

        service.sendText("!room:server", "hello").toCompletableFuture().join();

        assertEquals("PUT", transport.method);
        assertEquals("/_matrix/client/v3/rooms/%21room%3Aserver/send/m.room.message/txn42",
            transport.path);
    }

    @Test
    void sendText_returnsEventIdFromResponse() {
        transport.response = "{\"event_id\":\"$abc\"}";

        MessageReceipt receipt = service.sendText("!r:s", "hello").toCompletableFuture().join();

        assertEquals("$abc", receipt.eventId());
    }

    @Test
    void sendText_producesValidJsonForSpecialCharacters() throws Exception {
        transport.response = "{\"event_id\":\"$abc\"}";
        String body = "ligne1\nligne2\t\"cité\" \\ é 😀";

        service.sendText("!r:s", body).toCompletableFuture().join();

        JsonNode sent = mapper.readTree(transport.body);   // lève une exception si JSON invalide
        assertEquals("m.text", sent.path("msgtype").asText());
        assertEquals(body, sent.path("body").asText());
    }

    @Test
    void sendText_failsWhenResponseHasNoEventId() {
        transport.response = "{}";

        CompletionException ex = assertThrows(CompletionException.class,
            () -> service.sendText("!r:s", "hi").toCompletableFuture().join());

        assertInstanceOf(MatrixException.class, ex.getCause());
    }

    @Test
    void sendText_failsWhenResponseIsNotJson() {
        transport.response = "<html>oops</html>";

        CompletionException ex = assertThrows(CompletionException.class,
            () -> service.sendText("!r:s", "hi").toCompletableFuture().join());

        assertInstanceOf(MatrixException.class, ex.getCause());
    }
}
