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

    // ---- threads ------------------------------------------------------------------------------

    private JsonNode sentBody() throws Exception {
        ArgumentCaptor<String> sentBody = ArgumentCaptor.forClass(String.class);
        verify(transport).put(any(), any(), sentBody.capture());
        return mapper.readTree(sentBody.getValue());
    }

    @Test
    void sendText_hasNoRelationOutsideAThread() throws Exception {
        putReturns("{\"event_id\":\"$abc\"}");

        service.sendText("!r:s", "hello").toCompletableFuture().join();

        assertTrue(sentBody().path("m.relates_to").isMissingNode());
    }

    @Test
    void sendTextInThread_addsThreadRelationWithReplyFallbackToTheRoot() throws Exception {
        putReturns("{\"event_id\":\"$reply\"}");

        MessageReceipt receipt = service.sendTextInThread("!r:s", "$root", "dans le thread")
            .toCompletableFuture().join();

        assertEquals("$reply", receipt.eventId());
        JsonNode sent = sentBody();
        assertEquals("m.text", sent.path("msgtype").asText());
        assertEquals("dans le thread", sent.path("body").asText());
        JsonNode relatesTo = sent.path("m.relates_to");
        assertEquals("m.thread", relatesTo.path("rel_type").asText());
        assertEquals("$root", relatesTo.path("event_id").asText());
        assertTrue(relatesTo.path("is_falling_back").asBoolean());
        assertEquals("$root", relatesTo.path("m.in_reply_to").path("event_id").asText());
    }

    @Test
    void sendTextInThread_usesTheSameEndpointAsAPlainMessage() {
        putReturns("{\"event_id\":\"$abc\"}");

        service.sendTextInThread("!room:server", "$root", "hello").toCompletableFuture().join();

        verify(transport).put(
            eq("/_matrix/client/v3/rooms/%21room%3Aserver/send/m.room.message/txn42"), eq("tok"), any());
        verifyNoMoreInteractions(transport);
    }

    @Test
    void sendNoticeInThread_usesNoticeMsgtypeAndThreadRelation() throws Exception {
        putReturns("{\"event_id\":\"$abc\"}");

        service.sendNoticeInThread("!r:s", "$root", "Alerte").toCompletableFuture().join();

        JsonNode sent = sentBody();
        assertEquals("m.notice", sent.path("msgtype").asText());
        assertEquals("m.thread", sent.path("m.relates_to").path("rel_type").asText());
        assertEquals("$root", sent.path("m.relates_to").path("event_id").asText());
    }

    @Test
    void sendInThread_withoutThreadRoot_isRejectedBeforeAnyRequest() {
        assertThrows(IllegalArgumentException.class, () -> service.sendTextInThread("!r:s", null, "x"));
        assertThrows(IllegalArgumentException.class, () -> service.sendTextInThread("!r:s", " ", "x"));
        assertThrows(IllegalArgumentException.class, () -> service.sendNoticeInThread("!r:s", "", "x"));

        verifyNoMoreInteractions(transport);   // aucun appel HTTP n'a eu lieu
    }

    @Test
    void sendTextInThread_failsWhenResponseHasNoEventId() {
        putReturns("{}");

        CompletionException ex = assertThrows(CompletionException.class,
            () -> service.sendTextInThread("!r:s", "$root", "hi").toCompletableFuture().join());

        assertInstanceOf(MatrixException.class, ex.getCause());
    }
}
