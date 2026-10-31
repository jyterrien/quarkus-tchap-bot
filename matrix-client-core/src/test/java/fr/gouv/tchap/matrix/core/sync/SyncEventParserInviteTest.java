package fr.gouv.tchap.matrix.core.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SyncEventParserInviteTest {

    private final SyncEventParser parser = new SyncEventParser(new ObjectMapper());

    @Test
    void parseInvitedRoomIds_returnsAllInvitedRooms() {
        String json = "{\"next_batch\":\"s1\",\"rooms\":{\"invite\":{"
            + "\"!a:s\":{\"invite_state\":{\"events\":[]}},\"!b:s\":{}}}}";

        assertEquals(List.of("!a:s", "!b:s"), parser.parseInvitedRoomIds(json));
    }

    @Test
    void parseInvitedRoomIds_withoutInviteSection_isEmpty() {
        assertTrue(parser.parseInvitedRoomIds("{\"next_batch\":\"s1\"}").isEmpty());
        assertTrue(parser.parseInvitedRoomIds(
            "{\"next_batch\":\"s1\",\"rooms\":{\"join\":{\"!j:s\":{}}}}").isEmpty());
    }

    @Test
    void parseInvitedRoomIds_invalidJson_throws() {
        assertThrows(RuntimeException.class, () -> parser.parseInvitedRoomIds("not json"));
    }
}
