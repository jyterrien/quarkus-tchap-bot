package fr.gouv.tchap.matrix.core.transport;

import static fr.gouv.tchap.matrix.core.HttpTransportMocks.answeringEmptyJson;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import fr.gouv.tchap.matrix.transport.HttpTransport;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MatrixHttpApiTest {

	private final HttpTransport transport = answeringEmptyJson();
	private final MatrixHttpApi api = new MatrixHttpApi(transport, "tok");

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	@Test
	void joinRoom_usesPost() {
		api.joinRoom("!room:server").toCompletableFuture().join();

		verify(transport).post("/_matrix/client/v3/rooms/%21room%3Aserver/join", "tok", "{}");
		verifyNoMoreInteractions(transport);
	}

	@Test
	void leaveRoom_usesPost() {
		api.leaveRoom("!room:server").toCompletableFuture().join();

		verify(transport).post("/_matrix/client/v3/rooms/%21room%3Aserver/leave", "tok", "{}");
		verifyNoMoreInteractions(transport);
	}

	@Test
	void sendMessage_usesPut() {
		api.sendMessage("!room:server", "txn1", "{\"a\":1}").toCompletableFuture().join();

		verify(transport).put("/_matrix/client/v3/rooms/%21room%3Aserver/send/m.room.message/txn1", "tok", "{\"a\":1}");
		verifyNoMoreInteractions(transport);
	}

	@Test
	void sync_incremental_usesSinceAndNoFilter() {
		api.sync("s 1", 5000, 0).toCompletableFuture().join();

		verify(transport).get("/_matrix/client/v3/sync?timeout=5000&since=s+1", "tok");
		verifyNoMoreInteractions(transport);
	}

	@Test
	void sync_initial_withLimitZero_addsFilterWithoutHistory() {
		api.sync(null, 30000, 0).toCompletableFuture().join();

		String expectedFilter = enc("{\"room\":{\"timeline\":{\"limit\":0}}}");
		verify(transport).get("/_matrix/client/v3/sync?timeout=30000&filter=" + expectedFilter, "tok");
		verifyNoMoreInteractions(transport);
	}

	@Test
	void sync_initial_withLimit20_addsFilterWithThatLimit() {
		api.sync("", 30000, 20).toCompletableFuture().join();

		verify(transport).get(
				"/_matrix/client/v3/sync?timeout=30000&filter=" + enc("{\"room\":{\"timeline\":{\"limit\":20}}}"),
				"tok");
		verifyNoMoreInteractions(transport);
	}

	@Test
	void sync_initial_withNegativeLimit_hasNoFilter() {
		api.sync(null, 30000, -1).toCompletableFuture().join();

		verify(transport).get("/_matrix/client/v3/sync?timeout=30000", "tok");
		verifyNoMoreInteractions(transport);
	}

	@Test
	void sync_twoArgOverload_hasNoFilter() {
		api.sync(null, 1000).toCompletableFuture().join();

		verify(transport).get("/_matrix/client/v3/sync?timeout=1000", "tok");
		verifyNoMoreInteractions(transport);
	}
}
