package fr.gouv.tchap.quarkus.events;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.matrix.message.OutgoingMessage;
import fr.gouv.tchap.matrix.message.OutgoingMessage.Type;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TchapMessageTest {

	private static final Instant TS = Instant.parse("2026-11-15T10:00:00Z");

	private static TchapMessage message(String threadRootId, String replyToEventId) {
		return new TchapMessage("$msg", "!r:s", "@alice:s", "ping", TS, false, "m.text", threadRootId, replyToEventId);
	}

	@Test
	void reply_toAPlainMessage_isASimpleReply_thatOpensNoThread() {
		OutgoingMessage reply = message(null, null).reply("pong");

		assertEquals("pong", reply.body());
		assertEquals(Type.TEXT, reply.type());
		assertEquals("$msg", reply.replyToEventId());
		assertNull(reply.threadRootId());
	}

	@Test
	void reply_toAMessageInAThread_staysInThatThread() {
		OutgoingMessage reply = message("$root", null).reply("pong");

		assertEquals("$msg", reply.replyToEventId());
		assertEquals("$root", reply.threadRootId());
	}

	@Test
	void reply_canBeTurnedIntoANoticeWithHtml() {
		OutgoingMessage reply = message(null, null).reply("ok").withType(Type.NOTICE).withHtml("<b>ok</b>");

		assertEquals(Type.NOTICE, reply.type());
		assertEquals("<b>ok</b>", reply.formattedBody());
		assertEquals("$msg", reply.replyToEventId());
	}

	@Test
	void isReply_followsTheReplyTarget_notTheThread() {
		assertFalse(message("$root", null).isReply());
		assertTrue(message(null, "$q").isReply());
		assertTrue(message("$root", "$q").isReply());
	}

	@Test
	void olderConstructors_stillWork() {
		TchapMessage seven = new TchapMessage("$e", "!r:s", "@u:s", "hi", TS, false, "m.text");
		TchapMessage eight = new TchapMessage("$e", "!r:s", "@u:s", "hi", TS, false, "m.text", "$root");

		assertNull(seven.replyToEventId());
		assertNull(seven.threadRootId());
		assertNull(eight.replyToEventId());
		assertEquals("$root", eight.threadRootId());
	}

	@Test
	void replyThreadId_isUnchanged() {
		assertEquals("$msg", message(null, null).replyThreadId());
		assertEquals("$root", message("$root", null).replyThreadId());
	}

	@Test
	void olderConstructors_stillWork_withoutMedia() {
		TchapMessage nine = new TchapMessage("$e", "!r:s", "@u:s", "hi", TS, false, "m.text", "$root", "$q");

		assertNull(nine.media());
		assertFalse(nine.hasMedia());
		assertEquals("$q", nine.replyToEventId());
	}

	@Test
	void hasMedia_followsTheMediaField() {
		TchapMedia media = new TchapMedia("mxc://srv/abc", "image/png", 10L, "a.png");
		TchapMessage withMedia = new TchapMessage("$e", "!r:s", "@u:s", "a.png", TS, false, "m.image", null, null,
				media);

		assertTrue(withMedia.hasMedia());
		assertEquals("mxc://srv/abc", withMedia.media().uri().toString());
	}
}
