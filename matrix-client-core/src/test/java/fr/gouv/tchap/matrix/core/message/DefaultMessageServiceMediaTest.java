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
import fr.gouv.tchap.matrix.media.MxcUri;
import fr.gouv.tchap.matrix.message.MediaAttachment;
import fr.gouv.tchap.matrix.message.OutgoingMessage;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Contenu {@code m.room.message} des messages média. */
class DefaultMessageServiceMediaTest {

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

	private static MediaAttachment media() {
		return MediaAttachment.of(new MxcUri("srv", "abc"), "image/png", 18_432);
	}

	@Test
	void image_hasMsgtypeBodyUrlAndInfo() throws Exception {
		JsonNode sent = send(OutgoingMessage.image("graphique.png", media().withDimensions(800, 600)));

		assertEquals("m.image", sent.path("msgtype").asText());
		assertEquals("graphique.png", sent.path("body").asText());
		assertEquals("mxc://srv/abc", sent.path("url").asText());
		JsonNode info = sent.path("info");
		assertEquals("image/png", info.path("mimetype").asText());
		assertEquals(18_432, info.path("size").asLong());
		assertEquals(800, info.path("w").asInt());
		assertEquals(600, info.path("h").asInt());
		assertTrue(info.path("duration").isMissingNode());
	}

	@Test
	void file_withoutAnyMetadata_hasNoInfoObject() throws Exception {
		MediaAttachment bare = new MediaAttachment("mxc://srv/abc", null, null, null, null, null, null);

		JsonNode sent = send(OutgoingMessage.file("notes.pdf", bare));

		assertEquals("m.file", sent.path("msgtype").asText());
		assertEquals("mxc://srv/abc", sent.path("url").asText());
		assertTrue(sent.path("info").isMissingNode(), "pas d'objet info vide");
		assertTrue(sent.path("filename").isMissingNode());
	}

	@Test
	void audioAndVideo_carryTheirDuration() throws Exception {
		JsonNode audio = send(OutgoingMessage.audio("memo.ogg", media().withDuration(4_200)));

		assertEquals("m.audio", audio.path("msgtype").asText());
		assertEquals(4_200, audio.path("info").path("duration").asLong());
	}

	@Test
	void filename_isSentSoBodyCanBeACaption() throws Exception {
		JsonNode sent = send(OutgoingMessage.image("Voici le résultat", media().withFilename("chart.png")));

		assertEquals("Voici le résultat", sent.path("body").asText());
		assertEquals("chart.png", sent.path("filename").asText());
	}

	@Test
	void aCaptionCanHaveHtml() throws Exception {
		JsonNode sent = send(OutgoingMessage.image("Résultat", media()).withHtml("<b>Résultat</b>"));

		assertEquals("org.matrix.custom.html", sent.path("format").asText());
		assertEquals("<b>Résultat</b>", sent.path("formatted_body").asText());
		assertEquals("mxc://srv/abc", sent.path("url").asText());
	}

	@Test
	void aMediaReplyInAThread_keepsItsRelation() throws Exception {
		JsonNode sent = send(OutgoingMessage.image("a.png", media()).inReplyTo("$q").inThread("$root"));

		JsonNode relatesTo = sent.path("m.relates_to");
		assertEquals("m.thread", relatesTo.path("rel_type").asText());
		assertEquals("$root", relatesTo.path("event_id").asText());
		assertEquals("$q", relatesTo.path("m.in_reply_to").path("event_id").asText());
		assertEquals("mxc://srv/abc", sent.path("url").asText());
	}

	@Test
	void plainTextMessages_haveNoUrlNorInfo() throws Exception {
		JsonNode sent = send(OutgoingMessage.text("bonjour"));

		assertTrue(sent.path("url").isMissingNode());
		assertTrue(sent.path("info").isMissingNode());
	}

	@Test
	void editingAMediaMessage_isRejected_beforeAnyRequest() {
		assertThrows(IllegalArgumentException.class,
				() -> service.edit("!r:s", "$orig", OutgoingMessage.image("a.png", media())));

		verifyNoMoreInteractions(transport);
	}
}
