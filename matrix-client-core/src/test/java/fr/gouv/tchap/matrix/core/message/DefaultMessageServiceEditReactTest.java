package fr.gouv.tchap.matrix.core.message;

import static fr.gouv.tchap.matrix.core.HttpTransportMocks.answeringEmptyJson;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
 * Édition d'un message ({@code m.replace}) et réaction ({@code m.annotation}).
 */
class DefaultMessageServiceEditReactTest {

	private final HttpTransport transport = answeringEmptyJson();
	private final DefaultMessageService service = new DefaultMessageService(new MatrixHttpApi(transport, "tok"),
			() -> "txn7");

	private void putReturnsEventId(String eventId) {
		doReturn(CompletableFuture.completedFuture("{\"event_id\":\"" + eventId + "\"}")).when(transport).put(any(),
				any(), any());
	}

	private JsonNode lastSentContent() throws Exception {
		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(transport).put(any(), any(), body.capture());
		return new ObjectMapper().readTree(body.getValue());
	}

	// ---- édition
	// -----------------------------------------------------------------------------

	@Test
	void edit_putsAReplaceRelationAndTheNewContent_withAStarPrefixedFallback() throws Exception {
		putReturnsEventId("$edit");

		String receipt = service.edit("!r:s", "$orig", OutgoingMessage.text("nouveau texte")).toCompletableFuture()
				.join().eventId();

		assertEquals("$edit", receipt, "le reçu est celui de l'événement d'édition");
		JsonNode sent = lastSentContent();
		assertEquals("m.text", sent.path("msgtype").asText());
		assertEquals("* nouveau texte", sent.path("body").asText());
		assertEquals("nouveau texte", sent.path("m.new_content").path("body").asText());
		assertEquals("m.text", sent.path("m.new_content").path("msgtype").asText());
		assertEquals("m.replace", sent.path("m.relates_to").path("rel_type").asText());
		assertEquals("$orig", sent.path("m.relates_to").path("event_id").asText());
	}

	@Test
	void edit_goesToTheSameEndpointAsAMessage() {
		putReturnsEventId("$edit");

		service.edit("!room:server", "$orig", OutgoingMessage.text("x")).toCompletableFuture().join();

		verify(transport).put(eq("/_matrix/client/v3/rooms/%21room%3Aserver/send/m.room.message/txn7"), eq("tok"),
				any());
		verifyNoMoreInteractions(transport);
	}

	@Test
	void edit_withHtml_prefixesTheFallbackOnly_andKeepsTheNewContentClean() throws Exception {
		putReturnsEventId("$edit");

		service.edit("!r:s", "$orig", OutgoingMessage.notice("Terminé").withHtml("<b>Terminé</b>"))
				.toCompletableFuture().join();

		JsonNode sent = lastSentContent();
		assertEquals("m.notice", sent.path("msgtype").asText());
		assertEquals("org.matrix.custom.html", sent.path("format").asText());
		assertEquals("* <b>Terminé</b>", sent.path("formatted_body").asText());
		JsonNode newContent = sent.path("m.new_content");
		assertEquals("Terminé", newContent.path("body").asText());
		assertEquals("org.matrix.custom.html", newContent.path("format").asText());
		assertEquals("<b>Terminé</b>", newContent.path("formatted_body").asText());
		assertTrue(newContent.path("m.relates_to").isMissingNode(), "m.new_content ne porte aucune relation");
	}

	@Test
	void edit_rejectsAChangeOfReplyOrThread_beforeAnyRequest() {
		assertThrows(IllegalArgumentException.class,
				() -> service.edit("!r:s", "$orig", OutgoingMessage.text("x").inReplyTo("$q")));
		assertThrows(IllegalArgumentException.class,
				() -> service.edit("!r:s", "$orig", OutgoingMessage.text("x").inThread("$root")));

		verifyNoMoreInteractions(transport);
	}

	@Test
	void edit_rejectsMissingArguments_beforeAnyRequest() {
		assertThrows(IllegalArgumentException.class, () -> service.edit("!r:s", null, OutgoingMessage.text("x")));
		assertThrows(IllegalArgumentException.class, () -> service.edit("!r:s", " ", OutgoingMessage.text("x")));
		assertThrows(NullPointerException.class, () -> service.edit("!r:s", "$orig", null));

		verifyNoMoreInteractions(transport);
	}

	// ---- réaction
	// ----------------------------------------------------------------------------

	@Test
	void react_sendsAnAnnotationEvent_toTheReactionEndpoint() throws Exception {
		putReturnsEventId("$reaction");

		String receipt = service.react("!room:server", "$orig", "👍").toCompletableFuture().join().eventId();

		assertEquals("$reaction", receipt);
		verify(transport).put(eq("/_matrix/client/v3/rooms/%21room%3Aserver/send/m.reaction/txn7"), eq("tok"), any());
		JsonNode relatesTo = lastSentContent().path("m.relates_to");
		assertEquals("m.annotation", relatesTo.path("rel_type").asText());
		assertEquals("$orig", relatesTo.path("event_id").asText());
		assertEquals("👍", relatesTo.path("key").asText());
	}

	@Test
	void react_hasNoMessageFields() throws Exception {
		putReturnsEventId("$reaction");

		service.react("!r:s", "$orig", "✅").toCompletableFuture().join();

		JsonNode sent = lastSentContent();
		assertTrue(sent.path("msgtype").isMissingNode());
		assertTrue(sent.path("body").isMissingNode());
	}

	@Test
	void react_rejectsMissingArguments_beforeAnyRequest() {
		assertThrows(IllegalArgumentException.class, () -> service.react("!r:s", null, "👍"));
		assertThrows(IllegalArgumentException.class, () -> service.react("!r:s", "$orig", ""));
		assertThrows(IllegalArgumentException.class, () -> service.react("!r:s", "$orig", null));

		verifyNoMoreInteractions(transport);
	}
}
