package fr.gouv.tchap.matrix.core.transport;

import static fr.gouv.tchap.matrix.core.HttpTransportMocks.answeringEmptyJson;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import fr.gouv.tchap.matrix.transport.HttpTransport;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatrixHttpApiRoomsTest {

	private final HttpTransport transport = answeringEmptyJson();
	private final MatrixHttpApi api = new MatrixHttpApi(transport, "tok");

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	@Test
	void sync_initial_withRooms_filtersRoomsAndLimitsHistory() {
		api.sync(null, 30_000, 0, List.of("!a:s", "!b:s")).toCompletableFuture().join();

		String filter = "{\"room\":{\"rooms\":[\"!a:s\",\"!b:s\"],\"timeline\":{\"limit\":0}}}";
		verify(transport).get("/_matrix/client/v3/sync?timeout=30000&filter=" + enc(filter), "tok");
		verifyNoMoreInteractions(transport);
	}

	@Test
	void sync_incremental_withRooms_keepsRoomFilterButNoTimelineLimit() {
		api.sync("s1", 30_000, 0, List.of("!a:s")).toCompletableFuture().join();

		String filter = "{\"room\":{\"rooms\":[\"!a:s\"]}}";
		verify(transport).get("/_matrix/client/v3/sync?timeout=30000&since=s1&filter=" + enc(filter), "tok");
		verifyNoMoreInteractions(transport);
	}

	@Test
	void sync_incremental_withoutRooms_hasNoFilter() {
		api.sync("s1", 30_000, 0, List.of()).toCompletableFuture().join();

		verify(transport).get("/_matrix/client/v3/sync?timeout=30000&since=s1", "tok");
		verifyNoMoreInteractions(transport);
	}

	@Test
	void joinByIdOrAlias_postsToJoinEndpoint_withEncodedAlias() {
		api.joinByIdOrAlias("#astreinte:agent.tchap.gouv.fr").toCompletableFuture().join();

		verify(transport).post("/_matrix/client/v3/join/%23astreinte%3Aagent.tchap.gouv.fr", "tok", "{}");
		verifyNoMoreInteractions(transport);
	}

	@Test
	void whoami_usesGet() {
		api.whoami().toCompletableFuture().join();

		verify(transport).get("/_matrix/client/v3/account/whoami", "tok");
		verifyNoMoreInteractions(transport);
	}
}
