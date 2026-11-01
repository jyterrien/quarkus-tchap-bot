package fr.gouv.tchap.quarkus.events;

import fr.gouv.tchap.matrix.event.MatrixEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Test unitaire pur : aucun démarrage de Quarkus. */
class TchapMessageMapperTest {

    private static final String BOT = "@bot:tchap.gouv.fr";
    private static final Instant TS = Instant.parse("2026-10-31T10:00:00Z");

    private static MatrixEvent event(String type, String sender, String content) {
        return new MatrixEvent("$e1", "!room:s", sender, type, content, TS);
    }

    @Test
    void mapsRoomMessage() {
        TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

        Optional<TchapMessage> result = mapper.map(event("m.room.message", "@alice:s",
            "{\"msgtype\":\"m.notice\",\"body\":\"salut\"}"));

        TchapMessage m = result.orElseThrow();
        assertEquals("$e1", m.eventId());
        assertEquals("!room:s", m.roomId());
        assertEquals("@alice:s", m.sender());
        assertEquals("salut", m.body());
        assertEquals("m.notice", m.messageType());
        assertEquals(TS, m.timestamp());
        assertFalse(m.encrypted());
    }

    @Test
    void ignoresOtherEventTypes() {
        TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

        assertTrue(mapper.map(event("m.room.member", "@alice:s", "{\"membership\":\"join\"}")).isEmpty());
        assertTrue(mapper.map(event("m.room.encrypted", "@alice:s", "{}")).isEmpty());
    }

    @Test
    void ignoresOwnMessages_whenEnabled() {
        TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

        assertTrue(mapper.map(event("m.room.message", BOT, "{\"body\":\"echo\"}")).isEmpty());
    }

    @Test
    void keepsOwnMessages_whenIgnoreSelfDisabled() {
        TchapMessageMapper mapper = new TchapMessageMapper(false, BOT);

        assertTrue(mapper.map(event("m.room.message", BOT, "{\"body\":\"echo\"}")).isPresent());
    }

    @Test
    void keepsEverything_whenBotUserIdUnknown() {
        TchapMessageMapper mapper = new TchapMessageMapper(true, null);

        assertTrue(mapper.map(event("m.room.message", BOT, "{\"body\":\"echo\"}")).isPresent());
    }

    @Test
    void appliesDefaults_whenBodyAndMsgtypeAreMissing() {
        TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

        TchapMessage m = mapper.map(event("m.room.message", "@alice:s", "{}")).orElseThrow();

        assertEquals("", m.body());
        assertEquals("m.text", m.messageType());
    }

    @Test
    void invalidContent_isDroppedWithoutThrowing() {
        TchapMessageMapper mapper = new TchapMessageMapper(true, BOT);

        assertDoesNotThrow(() -> assertTrue(
            mapper.map(event("m.room.message", "@alice:s", "not json")).isEmpty()));
    }
}
