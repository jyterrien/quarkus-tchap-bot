package fr.gouv.tchap.matrix.core.message;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.matrix.message.OutgoingMessage;
import fr.gouv.tchap.matrix.message.OutgoingMessage.Type;
import org.junit.jupiter.api.Test;

class OutgoingMessageTest {

	@Test
	void factories_setTheType() {
		assertEquals(Type.TEXT, OutgoingMessage.text("a").type());
		assertEquals(Type.NOTICE, OutgoingMessage.notice("a").type());
		assertEquals(Type.EMOTE, OutgoingMessage.emote("a").type());
		assertEquals("m.text", Type.TEXT.msgtype());
		assertEquals("m.notice", Type.NOTICE.msgtype());
		assertEquals("m.emote", Type.EMOTE.msgtype());
	}

	@Test
	void aNewMessage_hasNoHtmlAndNoRelation() {
		OutgoingMessage m = OutgoingMessage.text("hello");

		assertNull(m.formattedBody());
		assertNull(m.replyToEventId());
		assertNull(m.threadRootId());
	}

	@Test
	void withMethods_returnACopy_andLeaveTheOriginalUntouched() {
		OutgoingMessage original = OutgoingMessage.text("hello");

		OutgoingMessage rich = original.withHtml("<b>hello</b>").inReplyTo("$a").inThread("$root")
				.withType(Type.NOTICE);

		assertEquals("<b>hello</b>", rich.formattedBody());
		assertEquals("$a", rich.replyToEventId());
		assertEquals("$root", rich.threadRootId());
		assertEquals(Type.NOTICE, rich.type());
		assertEquals("hello", rich.body());
		assertNull(original.formattedBody());
		assertNull(original.replyToEventId());
		assertEquals(Type.TEXT, original.type());
	}

	@Test
	void blankOrMissingBody_isRejected() {
		assertThrows(IllegalArgumentException.class, () -> OutgoingMessage.text(null));
		assertThrows(IllegalArgumentException.class, () -> OutgoingMessage.text(" "));
		assertThrows(IllegalArgumentException.class, () -> OutgoingMessage.notice(""));
		assertThrows(IllegalArgumentException.class, () -> OutgoingMessage.emote(null));
	}

	@Test
	void blankRelationsOrHtml_areRejected() {
		OutgoingMessage m = OutgoingMessage.text("x");

		assertThrows(IllegalArgumentException.class, () -> m.inReplyTo(" "));
		assertThrows(IllegalArgumentException.class, () -> m.inThread(""));
		assertThrows(IllegalArgumentException.class, () -> m.withHtml("  "));
	}

	@Test
	void nullRelationsOrHtml_meanNone() {
		OutgoingMessage m = OutgoingMessage.text("x").inReplyTo("$a").inThread("$r").withHtml("<i>x</i>");

		OutgoingMessage cleared = m.inReplyTo(null).inThread(null).withHtml(null);

		assertNull(cleared.replyToEventId());
		assertNull(cleared.threadRootId());
		assertNull(cleared.formattedBody());
	}

	@Test
	void escapeHtml_escapesTheFiveSpecialCharacters() {
		assertEquals("&lt;script&gt;alert(&quot;x&quot;) &amp; &#39;y&#39;&lt;/script&gt;",
				OutgoingMessage.escapeHtml("<script>alert(\"x\") & 'y'</script>"));
	}

	@Test
	void escapeHtml_leavesOrdinaryTextAlone_andHandlesNull() {
		assertEquals("Déploiement terminé 😀", OutgoingMessage.escapeHtml("Déploiement terminé 😀"));
		assertEquals("", OutgoingMessage.escapeHtml(null));
	}
}
