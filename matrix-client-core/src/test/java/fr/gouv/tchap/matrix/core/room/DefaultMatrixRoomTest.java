package fr.gouv.tchap.matrix.core.room;

import static fr.gouv.tchap.matrix.core.HttpTransportMocks.answeringEmptyJson;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.gouv.tchap.matrix.core.message.DefaultMessageService;
import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultMatrixRoomTest {

	private final HttpTransport transport = answeringEmptyJson();
	private final DefaultMatrixRoom room = new DefaultMatrixRoom("!oncall:s",
			new DefaultMessageService(new MatrixHttpApi(transport, "tok"), () -> "txn1"));

	private void putReturns(String response) {
		doReturn(CompletableFuture.completedFuture(response)).when(transport).put(any(), any(), any());
	}

	@Test
	void sendText_goesToTheBoundRoomOnly() throws Exception {
		putReturns("{\"event_id\":\"$e1\"}");

		String eventId = room.sendText("Base indisponible").toCompletableFuture().join().eventId();

		assertEquals("$e1", eventId);
		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(transport).put(eq("/_matrix/client/v3/rooms/%21oncall%3As/send/m.room.message/txn1"), eq("tok"),
				body.capture());
		verifyNoMoreInteractions(transport);
		assertEquals("m.text", new ObjectMapper().readTree(body.getValue()).path("msgtype").asText());
	}

	@Test
	void sendNotice_usesNoticeMsgtype() throws Exception {
		putReturns("{\"event_id\":\"$e2\"}");

		room.sendNotice("Alerte").toCompletableFuture().join();

		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(transport).put(any(), any(), body.capture());
		var sent = new ObjectMapper().readTree(body.getValue());
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

	@Test
	void sendTextInThread_goesToTheBoundRoomWithTheThreadRelation() throws Exception {
		putReturns("{\"event_id\":\"$e3\"}");

		String eventId = room.sendTextInThread("$root", "Suite").toCompletableFuture().join().eventId();

		assertEquals("$e3", eventId);
		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(transport).put(eq("/_matrix/client/v3/rooms/%21oncall%3As/send/m.room.message/txn1"), eq("tok"),
				body.capture());
		verifyNoMoreInteractions(transport);
		var sent = new ObjectMapper().readTree(body.getValue());
		assertEquals("m.text", sent.path("msgtype").asText());
		assertEquals("m.thread", sent.path("m.relates_to").path("rel_type").asText());
		assertEquals("$root", sent.path("m.relates_to").path("event_id").asText());
	}

	@Test
	void sendNoticeInThread_usesNoticeMsgtypeAndThreadRelation() throws Exception {
		putReturns("{\"event_id\":\"$e4\"}");

		room.sendNoticeInThread("$root", "Alerte").toCompletableFuture().join();

		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(transport).put(any(), any(), body.capture());
		var sent = new ObjectMapper().readTree(body.getValue());
		assertEquals("m.notice", sent.path("msgtype").asText());
		assertEquals("$root", sent.path("m.relates_to").path("event_id").asText());
	}
}
