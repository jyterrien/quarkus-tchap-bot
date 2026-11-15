package fr.gouv.tchap.matrix.core.sync;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.gouv.tchap.matrix.event.MatrixEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Édition d'un message ({@code m.replace}) reçue par /sync. */
class SyncEventParserEditTest {

	private final SyncEventParser parser = new SyncEventParser(new ObjectMapper());

	private MatrixEvent parse(String contentJson) {
		String json = "{\"next_batch\":\"s1\",\"rooms\":{\"join\":{\"!r:s\":{\"timeline\":{\"events\":["
				+ "{\"event_id\":\"$e1\",\"sender\":\"@u:s\",\"type\":\"m.room.message\",\"origin_server_ts\":1,"
				+ "\"content\":" + contentJson + "}]}}}}}";
		return parser.parseEvents(json).get(0);
	}

	@Test
	void edit_exposesTheReplacedMessage() {
		MatrixEvent ev = parse("{\"msgtype\":\"m.text\",\"body\":\"* v2\",\"m.new_content\":{\"msgtype\":\"m.text\","
				+ "\"body\":\"v2\"},\"m.relates_to\":{\"rel_type\":\"m.replace\",\"event_id\":\"$orig\"}}");

		assertEquals("$orig", ev.replacesEventId());
		assertTrue(ev.isEdit());
		assertNull(ev.threadRootId());
		assertNull(ev.replyToEventId());
	}

	@Test
	void plainMessage_isNotAnEdit() {
		MatrixEvent ev = parse("{\"msgtype\":\"m.text\",\"body\":\"salut\"}");

		assertNull(ev.replacesEventId());
		assertFalse(ev.isEdit());
	}

	@Test
	void otherRelations_areNotEdits() {
		assertNull(parse("{\"m.relates_to\":{\"rel_type\":\"m.thread\",\"event_id\":\"$r\"}}").replacesEventId());
		assertNull(parse("{\"m.relates_to\":{\"m.in_reply_to\":{\"event_id\":\"$q\"}}}").replacesEventId());
		assertNull(parse("{\"m.relates_to\":{\"rel_type\":\"m.annotation\",\"event_id\":\"$o\",\"key\":\"x\"}}")
				.replacesEventId());
	}

	@Test
	void malformedEditRelation_isIgnored() {
		assertNull(parse("{\"m.relates_to\":{\"rel_type\":\"m.replace\"}}").replacesEventId());
		assertNull(parse("{\"m.relates_to\":{\"rel_type\":\"m.replace\",\"event_id\":42}}").replacesEventId());
		assertNull(parse("{\"m.relates_to\":{\"rel_type\":\"m.replace\",\"event_id\":\" \"}}").replacesEventId());
		assertNull(parse("{\"m.relates_to\":\"oops\"}").replacesEventId());
	}

	@Test
	void olderConstructors_stillWork_withoutEdit() {
		MatrixEvent six = new MatrixEvent("$e", "!r:s", "@u:s", "m.room.message", "{}", Instant.EPOCH);
		MatrixEvent seven = new MatrixEvent("$e", "!r:s", "@u:s", "m.room.message", "{}", Instant.EPOCH, "$root");
		MatrixEvent eight = new MatrixEvent("$e", "!r:s", "@u:s", "m.room.message", "{}", Instant.EPOCH, "$root", "$q");

		assertFalse(six.isEdit());
		assertFalse(seven.isEdit());
		assertFalse(eight.isEdit());
		assertEquals("$q", eight.replyToEventId());
	}
}
