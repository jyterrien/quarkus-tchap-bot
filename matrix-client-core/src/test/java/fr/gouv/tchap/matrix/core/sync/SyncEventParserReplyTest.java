package fr.gouv.tchap.matrix.core.sync;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.gouv.tchap.matrix.event.MatrixEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Réponse à un message précis ({@code m.in_reply_to}) reçue par /sync. */
class SyncEventParserReplyTest {

	private final SyncEventParser parser = new SyncEventParser(new ObjectMapper());

	private MatrixEvent parse(String contentJson) {
		String json = "{\"next_batch\":\"s1\",\"rooms\":{\"join\":{\"!r:s\":{\"timeline\":{\"events\":["
				+ "{\"event_id\":\"$e1\",\"sender\":\"@u:s\",\"type\":\"m.room.message\",\"origin_server_ts\":1,"
				+ "\"content\":" + contentJson + "}]}}}}}";
		return parser.parseEvents(json).get(0);
	}

	@Test
	void plainReply_exposesTheTargetEvent() {
		MatrixEvent ev = parse("{\"msgtype\":\"m.text\",\"body\":\"re\","
				+ "\"m.relates_to\":{\"m.in_reply_to\":{\"event_id\":\"$question\"}}}");

		assertEquals("$question", ev.replyToEventId());
		assertTrue(ev.isReply());
		assertNull(ev.threadRootId());
	}

	@Test
	void replyInAThread_exposesBothTheRootAndTheTarget() {
		MatrixEvent ev = parse("{\"msgtype\":\"m.text\",\"body\":\"re\",\"m.relates_to\":{\"rel_type\":\"m.thread\","
				+ "\"event_id\":\"$root\",\"is_falling_back\":false,\"m.in_reply_to\":{\"event_id\":\"$question\"}}}");

		assertEquals("$root", ev.threadRootId());
		assertEquals("$question", ev.replyToEventId());
	}

	@Test
	void theFallbackReplyOfAThreadMessage_isNotARealReply() {
		MatrixEvent ev = parse("{\"msgtype\":\"m.text\",\"body\":\"re\",\"m.relates_to\":{\"rel_type\":\"m.thread\","
				+ "\"event_id\":\"$root\",\"is_falling_back\":true,\"m.in_reply_to\":{\"event_id\":\"$root\"}}}");

		assertEquals("$root", ev.threadRootId());
		assertNull(ev.replyToEventId());
		assertFalse(ev.isReply());
	}

	@Test
	void messageWithoutRelation_isNotAReply() {
		MatrixEvent ev = parse("{\"msgtype\":\"m.text\",\"body\":\"salut\"}");

		assertNull(ev.replyToEventId());
		assertFalse(ev.isReply());
	}

	@Test
	void malformedReplyRelation_isIgnored() {
		assertNull(parse("{\"m.relates_to\":{\"m.in_reply_to\":{}}}").replyToEventId());
		assertNull(parse("{\"m.relates_to\":{\"m.in_reply_to\":{\"event_id\":42}}}").replyToEventId());
		assertNull(parse("{\"m.relates_to\":{\"m.in_reply_to\":{\"event_id\":\" \"}}}").replyToEventId());
		assertNull(parse("{\"m.relates_to\":\"oops\"}").replyToEventId());
	}

	@Test
	void editsAndReactions_areNotReplies() {
		assertNull(parse("{\"m.relates_to\":{\"rel_type\":\"m.replace\",\"event_id\":\"$orig\"}}").replyToEventId());
		assertNull(parse("{\"m.relates_to\":{\"rel_type\":\"m.annotation\",\"event_id\":\"$orig\",\"key\":\"👍\"}}")
				.replyToEventId());
	}

	@Test
	void olderConstructors_stillWork_withoutReply() {
		MatrixEvent six = new MatrixEvent("$e", "!r:s", "@u:s", "m.room.message", "{}", Instant.EPOCH);
		MatrixEvent seven = new MatrixEvent("$e", "!r:s", "@u:s", "m.room.message", "{}", Instant.EPOCH, "$root");

		assertNull(six.replyToEventId());
		assertNull(seven.replyToEventId());
		assertEquals("$root", seven.threadRootId());
	}
}
