package fr.gouv.tchap.matrix.core.message;

import static fr.gouv.tchap.matrix.core.HttpTransportMocks.answeringEmptyJson;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.message.OutgoingMessage;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Messages enrichis : emote, HTML, réponse à un message précis, réponse dans un
 * thread.
 */
class DefaultMessageServiceRichTest {

	private final HttpTransport transport = answeringEmptyJson();
	private final DefaultMessageService service = new DefaultMessageService(new MatrixHttpApi(transport, "tok"),
			() -> "txn7");

	private JsonNode send(OutgoingMessage message) throws Exception {
		doReturn(CompletableFuture.completedFuture("{\"event_id\":\"$new\"}")).when(transport).put(any(), any(), any());
		service.send("!r:s", message).toCompletableFuture().join();
		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(transport).put(any(), any(), body.capture());
		return new ObjectMapper().readTree(body.getValue());
	}

	@Test
	void emote_usesTheEmoteMsgtype() throws Exception {
		JsonNode sent = send(OutgoingMessage.emote("se met au travail"));

		assertEquals("m.emote", sent.path("msgtype").asText());
		assertEquals("se met au travail", sent.path("body").asText());
		assertTrue(sent.path("m.relates_to").isMissingNode());
		assertTrue(sent.path("formatted_body").isMissingNode());
	}

	@Test
	void html_addsFormatAndFormattedBody_andKeepsThePlainBodyAsFallback() throws Exception {
		JsonNode sent = send(OutgoingMessage.notice("Déploiement terminé").withHtml("<b>Déploiement</b> terminé"));

		assertEquals("m.notice", sent.path("msgtype").asText());
		assertEquals("Déploiement terminé", sent.path("body").asText());
		assertEquals("org.matrix.custom.html", sent.path("format").asText());
		assertEquals("<b>Déploiement</b> terminé", sent.path("formatted_body").asText());
	}

	@Test
	void plainReply_hasOnlyAnInReplyToRelation_andNoThread() throws Exception {
		JsonNode sent = send(OutgoingMessage.text("pong").inReplyTo("$question"));

		JsonNode relatesTo = sent.path("m.relates_to");
		assertEquals("$question", relatesTo.path("m.in_reply_to").path("event_id").asText());
		assertTrue(relatesTo.path("rel_type").isMissingNode(), "une réponse simple n'ouvre pas de thread");
		assertTrue(relatesTo.path("is_falling_back").isMissingNode());
	}

	@Test
	void replyInsideAThread_staysInTheThread_andPointsTheRealTarget() throws Exception {
		JsonNode sent = send(OutgoingMessage.text("pong").inReplyTo("$question").inThread("$root"));

		JsonNode relatesTo = sent.path("m.relates_to");
		assertEquals("m.thread", relatesTo.path("rel_type").asText());
		assertEquals("$root", relatesTo.path("event_id").asText());
		assertFalse(relatesTo.path("is_falling_back").asBoolean(true), "vraie réponse : pas un repli");
		assertEquals("$question", relatesTo.path("m.in_reply_to").path("event_id").asText());
	}

	@Test
	void threadWithoutReply_keepsTheFallbackToTheRoot() throws Exception {
		JsonNode sent = send(OutgoingMessage.text("suite").inThread("$root"));

		JsonNode relatesTo = sent.path("m.relates_to");
		assertEquals("m.thread", relatesTo.path("rel_type").asText());
		assertTrue(relatesTo.path("is_falling_back").asBoolean());
		assertEquals("$root", relatesTo.path("m.in_reply_to").path("event_id").asText());
	}

	@Test
	void everything_together_producesValidJson() throws Exception {
		JsonNode sent = send(OutgoingMessage.emote("a \"cité\" é 😀").withHtml("<i>a &quot;cité&quot;</i>")
				.inReplyTo("$q").inThread("$root"));

		assertEquals("m.emote", sent.path("msgtype").asText());
		assertEquals("a \"cité\" é 😀", sent.path("body").asText());
		assertEquals("org.matrix.custom.html", sent.path("format").asText());
		assertEquals("$q", sent.path("m.relates_to").path("m.in_reply_to").path("event_id").asText());
	}

	@Test
	void send_usesTheSameEndpointAndTheGeneratedTransactionId() throws Exception {
		send(OutgoingMessage.text("x"));

		verify(transport).put(
				org.mockito.ArgumentMatchers.eq("/_matrix/client/v3/rooms/%21r%3As/send/m.room.message/txn7"),
				org.mockito.ArgumentMatchers.eq("tok"), any());
		verifyNoMoreInteractions(transport);
	}

	@Test
	void send_returnsTheEventIdOfTheNewMessage() {
		doReturn(CompletableFuture.completedFuture("{\"event_id\":\"$abc\"}")).when(transport).put(any(), any(), any());

		assertEquals("$abc", service.send("!r:s", OutgoingMessage.text("x")).toCompletableFuture().join().eventId());
	}

	@Test
	void nullMessage_isRejectedBeforeAnyRequest() {
		assertThrows(NullPointerException.class, () -> service.send("!r:s", null));

		verifyNoMoreInteractions(transport);
	}

	@Test
	void theOlderSendMethods_stillProduceTheSameContent() throws Exception {
		doReturn(CompletableFuture.completedFuture("{\"event_id\":\"$e\"}")).when(transport).put(any(), any(), any());

		service.sendNoticeInThread("!r:s", "$root", "Alerte").toCompletableFuture().join();

		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(transport).put(any(), any(), body.capture());
		assertEquals("{\"msgtype\":\"m.notice\",\"body\":\"Alerte\",\"m.relates_to\":{\"rel_type\":\"m.thread\","
				+ "\"event_id\":\"$root\",\"is_falling_back\":true,\"m.in_reply_to\":{\"event_id\":\"$root\"}}}",
				body.getValue());
	}
}
