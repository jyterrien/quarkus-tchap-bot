package fr.gouv.tchap.matrix.core.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.message.MessageReceipt;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static fr.gouv.tchap.matrix.core.HttpTransportMocks.answeringEmptyJson;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class DefaultMessageServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpTransport transport = answeringEmptyJson();
    private final DefaultMessageService service =
        new DefaultMessageService(new MatrixHttpApi(transport, "tok"), () -> "txn42");

    private void putReturns(String response) {
        doReturn(CompletableFuture.completedFuture(response)).when(transport).put(any(), any(), any());
    }

    @Test
    void sendText_putsToCorrectPathWithTxnId() {
        putReturns("{\"event_id\":\"$abc\"}");

        service.sendText("!room:server", "hello").toCompletableFuture().join();

        verify(transport).put(
            eq("/_matrix/client/v3/rooms/%21room%3Aserver/send/m.room.message/txn42"), eq("tok"), any());
        verifyNoMoreInteractions(transport);
    }

    @Test
    void sendText_returnsEventIdFromResponse() {
        putReturns("{\"event_id\":\"$abc\"}");

        MessageReceipt receipt = service.sendText("!r:s", "hello").toCompletableFuture().join();

        assertEquals("$abc", receipt.eventId());
    }

    @Test
    void sendText_producesValidJsonForSpecialCharacters() throws Exception {
        putReturns("{\"event_id\":\"$abc\"}");
        String body = "ligne1\nligne2\t\"cité\" \\ é 😀";

        service.sendText("!r:s", body).toCompletableFuture().join();

        ArgumentCaptor<String> sentBody = ArgumentCaptor.forClass(String.class);
        verify(transport).put(any(), any(), sentBody.capture());
        JsonNode sent = mapper.readTree(sentBody.getValue());   // lève une exception si JSON invalide
        assertEquals("m.text", sent.path("msgtype").asText());
        assertEquals(body, sent.path("body").asText());
    }

    @Test
    void sendText_failsWhenResponseHasNoEventId() {
        putReturns("{}");

        CompletionException ex = assertThrows(CompletionException.class,
            () -> service.sendText("!r:s", "hi").toCompletableFuture().join());

        assertInstanceOf(MatrixException.class, ex.getCause());
    }

    @Test
    void sendText_failsWhenResponseIsNotJson() {
        putReturns("<html>oops</html>");

        CompletionException ex = assertThrows(CompletionException.class,
            () -> service.sendText("!r:s", "hi").toCompletableFuture().join());

        assertInstanceOf(MatrixException.class, ex.getCause());
    }
}
