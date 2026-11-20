package fr.gouv.tchap.matrix.core.media;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.gouv.tchap.matrix.core.message.DefaultMessageService;
import fr.gouv.tchap.matrix.core.room.DefaultMatrixRoom;
import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Téléverser puis envoyer dans un salon, de bout en bout du SDK. */
class MatrixRoomMediaTest {

	private final HttpTransport transport = mock(HttpTransport.class);
	private final MatrixHttpApi api = new MatrixHttpApi(transport, "tok");
	private final DefaultMatrixRoom room = new DefaultMatrixRoom("!oncall:s",
			new DefaultMessageService(api, () -> "txn1"), new DefaultMediaService(api));

	@Test
	void sendMedia_uploadsThenSendsAnImageMessageToTheRoom() throws Exception {
		doReturn(CompletableFuture.completedFuture("{\"content_uri\":\"mxc://srv/up1\"}")).when(transport).send(any());
		doReturn(CompletableFuture.completedFuture("{\"event_id\":\"$msg\"}")).when(transport).put(any(), any(), any());

		String eventId = room.sendMedia(new byte[]{1, 2, 3}, "image/png", "chart.png", "Mon graphique")
				.toCompletableFuture().join().eventId();

		assertEquals("$msg", eventId);
		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(transport).put(
				org.mockito.ArgumentMatchers.eq("/_matrix/client/v3/rooms/%21oncall%3As/send/m.room.message/txn1"),
				org.mockito.ArgumentMatchers.eq("tok"), body.capture());
		JsonNode sent = new ObjectMapper().readTree(body.getValue());
		assertEquals("m.image", sent.path("msgtype").asText());
		assertEquals("Mon graphique", sent.path("body").asText());
		assertEquals("mxc://srv/up1", sent.path("url").asText());
		assertEquals("chart.png", sent.path("filename").asText());
		assertEquals(3, sent.path("info").path("size").asInt());
		assertEquals("image/png", sent.path("info").path("mimetype").asText());
	}

	@Test
	void aRoomWithoutMediaService_refusesSendMedia() {
		DefaultMatrixRoom bare = new DefaultMatrixRoom("!oncall:s", new DefaultMessageService(api, () -> "txn1"));

		assertThrows(IllegalStateException.class, () -> bare.sendMedia(new byte[]{1}, "image/png", "a.png", null));
	}

	@Test
	void ifTheUploadFails_nothingIsSentToTheRoom() {
		doReturn(CompletableFuture.failedFuture(new fr.gouv.tchap.matrix.exception.MatrixException("down")))
				.when(transport).send(any());

		assertThrows(java.util.concurrent.CompletionException.class,
				() -> room.sendMedia(new byte[]{1}, "image/png", "a.png", null).toCompletableFuture().join());

		org.mockito.Mockito.verify(transport, org.mockito.Mockito.never()).put(any(), any(), any());
	}
}
