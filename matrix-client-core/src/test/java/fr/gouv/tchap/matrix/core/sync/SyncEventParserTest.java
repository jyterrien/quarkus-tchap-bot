package fr.gouv.tchap.matrix.core.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.gouv.tchap.matrix.event.MatrixEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Extraction du token et des événements ; les invitations sont dans {@link SyncEventParserInviteTest}. */
class SyncEventParserTest {

    private final SyncEventParser parser = new SyncEventParser(new ObjectMapper());

    private static String syncWithJoin(String joinBody) {
        return "{\"next_batch\":\"s1\",\"rooms\":{\"join\":{" + joinBody + "}}}";
    }

    private static String room(String roomId, String eventsJson) {
        return "\"" + roomId + "\":{\"timeline\":{\"events\":" + eventsJson + "}}";
    }

    @Test
    void extractNextBatch_returnsTheToken() {
        assertEquals("s72_9", parser.extractNextBatch("{\"next_batch\":\"s72_9\"}"));
    }

    @Test
    void extractNextBatch_isNullWhenAbsent() {
        assertNull(parser.extractNextBatch("{}"));
    }

    @Test
    void parseEvents_mapsAllFieldsOfAnEvent() {
        String json = syncWithJoin(room("!r:s", "[{\"event_id\":\"$e1\",\"sender\":\"@u:s\","
            + "\"type\":\"m.room.message\",\"origin_server_ts\":1700000000000,"
            + "\"content\":{\"msgtype\":\"m.text\",\"body\":\"hi\"}}]"));

        List<MatrixEvent> events = parser.parseEvents(json);

        assertEquals(List.of(new MatrixEvent("$e1", "!r:s", "@u:s", "m.room.message",
            "{\"msgtype\":\"m.text\",\"body\":\"hi\"}", Instant.ofEpochMilli(1_700_000_000_000L))), events);
    }

    @Test
    void parseEvents_skipsEventsWithoutEventId() {
        String json = syncWithJoin(room("!r:s",
            "[{\"sender\":\"@u:s\",\"type\":\"m.room.message\",\"content\":{}},"
                + "{\"event_id\":\"$ok\",\"sender\":\"@u:s\",\"type\":\"m.room.message\",\"content\":{}}]"));

        List<MatrixEvent> events = parser.parseEvents(json);

        assertEquals(List.of("$ok"), events.stream().map(MatrixEvent::eventId).toList());
    }

    @Test
    void parseEvents_missingTimestamp_defaultsToTheEpoch() {
        String json = syncWithJoin(room("!r:s",
            "[{\"event_id\":\"$e1\",\"sender\":\"@u:s\",\"type\":\"m.room.message\",\"content\":{}}]"));

        assertEquals(Instant.EPOCH, parser.parseEvents(json).get(0).timestamp());
    }

    @Test
    void parseEvents_returnsTheEventsOfEveryJoinedRoom_inServerOrder() {
        String json = syncWithJoin(
            room("!a:s", "[{\"event_id\":\"$e1\",\"content\":{}}]") + ","
                + room("!b:s", "[{\"event_id\":\"$e2\",\"content\":{}}]"));

        List<MatrixEvent> events = parser.parseEvents(json);

        assertEquals(List.of("$e1", "$e2"), events.stream().map(MatrixEvent::eventId).toList());
        assertEquals(List.of("!a:s", "!b:s"), events.stream().map(MatrixEvent::roomId).toList());
    }

    @Test
    void parseEvents_ignoresRoomsWhoseTimelineHasNoEventArray() {
        String json = "{\"rooms\":{\"join\":{\"!a:s\":{\"timeline\":{\"events\":{}}},\"!b:s\":{}}}}";

        assertTrue(parser.parseEvents(json).isEmpty());
    }

    @Test
    void parseEvents_ignoresInvitedRooms() {
        String json = "{\"rooms\":{\"invite\":{\"!i:s\":{\"invite_state\":{\"events\":["
            + "{\"event_id\":\"$x\",\"type\":\"m.room.member\",\"content\":{}}]}}}}}";

        assertTrue(parser.parseEvents(json).isEmpty());
    }

    @Test
    void parseEvents_withoutRoomsSection_returnsNothing() {
        assertTrue(parser.parseEvents("{\"next_batch\":\"s1\"}").isEmpty());
    }

    @Test
    void invalidJson_isRejectedByEveryMethod() {
        String invalid = "{";

        assertEquals("Invalid /sync response",
            assertThrows(RuntimeException.class, () -> parser.extractNextBatch(invalid)).getMessage());
        assertEquals("Invalid /sync response",
            assertThrows(RuntimeException.class, () -> parser.parseEvents(invalid)).getMessage());
        assertEquals("Invalid /sync response",
            assertThrows(RuntimeException.class, () -> parser.parseInvitedRoomIds(invalid)).getMessage());
    }

    // ---- threads ------------------------------------------------------------------------------

    private List<MatrixEvent> parseOneEventWithContent(String contentJson) {
        return parser.parseEvents(syncWithJoin(room("!r:s",
            "[{\"event_id\":\"$e1\",\"sender\":\"@u:s\",\"type\":\"m.room.message\","
                + "\"content\":" + contentJson + "}]")));
    }

    @Test
    void parseEvents_threadReply_exposesTheThreadRoot() {
        List<MatrixEvent> events = parseOneEventWithContent(
            "{\"msgtype\":\"m.text\",\"body\":\"re\",\"m.relates_to\":{\"rel_type\":\"m.thread\","
                + "\"event_id\":\"$root\",\"is_falling_back\":true,"
                + "\"m.in_reply_to\":{\"event_id\":\"$root\"}}}");

        assertEquals("$root", events.get(0).threadRootId());
        assertTrue(events.get(0).isInThread());
    }

    @Test
    void parseEvents_plainMessage_isNotInAThread() {
        MatrixEvent event = parseOneEventWithContent("{\"msgtype\":\"m.text\",\"body\":\"hi\"}").get(0);

        assertNull(event.threadRootId());
        assertFalse(event.isInThread());
    }

    @Test
    void parseEvents_otherRelations_areNotThreads() {
        // réponse simple (sans rel_type), édition et réaction : aucune n'est un thread
        assertNull(parseOneEventWithContent(
            "{\"m.relates_to\":{\"m.in_reply_to\":{\"event_id\":\"$x\"}}}").get(0).threadRootId());
        assertNull(parseOneEventWithContent(
            "{\"m.relates_to\":{\"rel_type\":\"m.replace\",\"event_id\":\"$x\"}}").get(0).threadRootId());
        assertNull(parseOneEventWithContent(
            "{\"m.relates_to\":{\"rel_type\":\"m.annotation\",\"event_id\":\"$x\"}}").get(0).threadRootId());
    }

    @Test
    void parseEvents_malformedThreadRelation_isIgnored() {
        assertNull(parseOneEventWithContent(
            "{\"m.relates_to\":{\"rel_type\":\"m.thread\"}}").get(0).threadRootId());
        assertNull(parseOneEventWithContent(
            "{\"m.relates_to\":{\"rel_type\":\"m.thread\",\"event_id\":42}}").get(0).threadRootId());
        assertNull(parseOneEventWithContent("{\"m.relates_to\":\"oops\"}").get(0).threadRootId());
    }

    @Test
    void threadRoot_doesNotAffectTheSixArgumentConstructor() {
        MatrixEvent event = new MatrixEvent("$e", "!r:s", "@u:s", "m.room.message", "{}", Instant.EPOCH);

        assertNull(event.threadRootId());
    }
}
