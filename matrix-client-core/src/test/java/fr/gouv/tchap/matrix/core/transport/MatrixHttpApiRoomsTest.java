package fr.gouv.tchap.matrix.core.transport;

import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MatrixHttpApiRoomsTest {

    private final RecordingHttpTransport transport = new RecordingHttpTransport();
    private final MatrixHttpApi api = new MatrixHttpApi(transport, "tok");

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Test
    void sync_initial_withRooms_filtersRoomsAndLimitsHistory() {
        api.sync(null, 30_000, 0, List.of("!a:s", "!b:s")).toCompletableFuture().join();

        String filter = "{\"room\":{\"rooms\":[\"!a:s\",\"!b:s\"],\"timeline\":{\"limit\":0}}}";
        assertEquals("/_matrix/client/v3/sync?timeout=30000&filter=" + enc(filter), transport.path);
    }

    @Test
    void sync_incremental_withRooms_keepsRoomFilterButNoTimelineLimit() {
        api.sync("s1", 30_000, 0, List.of("!a:s")).toCompletableFuture().join();

        String filter = "{\"room\":{\"rooms\":[\"!a:s\"]}}";
        assertEquals("/_matrix/client/v3/sync?timeout=30000&since=s1&filter=" + enc(filter), transport.path);
    }

    @Test
    void sync_incremental_withoutRooms_hasNoFilter() {
        api.sync("s1", 30_000, 0, List.of()).toCompletableFuture().join();

        assertEquals("/_matrix/client/v3/sync?timeout=30000&since=s1", transport.path);
    }

    @Test
    void joinByIdOrAlias_postsToJoinEndpoint_withEncodedAlias() {
        api.joinByIdOrAlias("#astreinte:agent.tchap.gouv.fr").toCompletableFuture().join();

        assertEquals("POST", transport.method);
        assertEquals("/_matrix/client/v3/join/%23astreinte%3Aagent.tchap.gouv.fr", transport.path);
        assertEquals("{}", transport.body);
        assertEquals("tok", transport.token);
    }

    @Test
    void whoami_usesGet() {
        api.whoami().toCompletableFuture().join();

        assertEquals("GET", transport.method);
        assertEquals("/_matrix/client/v3/account/whoami", transport.path);
        assertEquals("tok", transport.token);
    }
}
