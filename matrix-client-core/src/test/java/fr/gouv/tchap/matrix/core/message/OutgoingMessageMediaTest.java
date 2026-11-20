package fr.gouv.tchap.matrix.core.message;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.matrix.media.MxcUri;
import fr.gouv.tchap.matrix.message.MediaAttachment;
import fr.gouv.tchap.matrix.message.OutgoingMessage;
import fr.gouv.tchap.matrix.message.OutgoingMessage.Type;
import org.junit.jupiter.api.Test;

class OutgoingMessageMediaTest {

	private static final MediaAttachment MEDIA = MediaAttachment.of(new MxcUri("srv", "abc"), "image/png", 100);

	@Test
	void mediaFactories_setTheTypeAndTheMedia() {
		assertEquals(Type.IMAGE, OutgoingMessage.image("a.png", MEDIA).type());
		assertEquals(Type.FILE, OutgoingMessage.file("a.pdf", MEDIA).type());
		assertEquals(Type.AUDIO, OutgoingMessage.audio("a.ogg", MEDIA).type());
		assertEquals(Type.VIDEO, OutgoingMessage.video("a.mp4", MEDIA).type());
		assertSame(MEDIA, OutgoingMessage.image("a.png", MEDIA).media());
		assertEquals("m.image", Type.IMAGE.msgtype());
		assertEquals("m.file", Type.FILE.msgtype());
		assertEquals("m.audio", Type.AUDIO.msgtype());
		assertEquals("m.video", Type.VIDEO.msgtype());
	}

	@Test
	void onlyTheMediaTypes_reportIsMedia() {
		assertTrue(Type.IMAGE.isMedia());
		assertTrue(Type.FILE.isMedia());
		assertTrue(Type.AUDIO.isMedia());
		assertTrue(Type.VIDEO.isMedia());
		assertFalse(Type.TEXT.isMedia());
		assertFalse(Type.NOTICE.isMedia());
		assertFalse(Type.EMOTE.isMedia());
	}

	@Test
	void aMediaType_needsAMedia() {
		assertThrows(IllegalArgumentException.class, () -> OutgoingMessage.image("a.png", null));
		assertThrows(IllegalArgumentException.class,
				() -> new OutgoingMessage(Type.FILE, "a.pdf", null, null, null, null));
	}

	@Test
	void aTextType_cannotCarryAMedia() {
		assertThrows(IllegalArgumentException.class,
				() -> new OutgoingMessage(Type.TEXT, "x", null, null, null, MEDIA));
		assertThrows(IllegalArgumentException.class,
				() -> new OutgoingMessage(Type.NOTICE, "x", null, null, null, MEDIA));
	}

	@Test
	void withType_cannotCrossBetweenTextAndMediaTypes() {
		assertThrows(IllegalArgumentException.class, () -> OutgoingMessage.image("a.png", MEDIA).withType(Type.TEXT));
		assertThrows(IllegalArgumentException.class, () -> OutgoingMessage.text("x").withType(Type.IMAGE));
		assertEquals(Type.FILE, OutgoingMessage.image("a.png", MEDIA).withType(Type.FILE).type(),
				"d'un type média à un autre, le média suit");
	}

	@Test
	void withHtmlReplyAndThread_keepTheMedia() {
		OutgoingMessage m = OutgoingMessage.image("Légende", MEDIA).withHtml("<i>Légende</i>").inReplyTo("$q")
				.inThread("$root");

		assertSame(MEDIA, m.media());
		assertEquals("<i>Légende</i>", m.formattedBody());
		assertEquals("$q", m.replyToEventId());
		assertEquals("$root", m.threadRootId());
	}

	@Test
	void aBlankBody_isStillRefusedForMedia() {
		assertThrows(IllegalArgumentException.class, () -> OutgoingMessage.image(" ", MEDIA));
	}

	@Test
	void theFiveArgumentConstructor_staysValid_withoutMedia() {
		OutgoingMessage m = new OutgoingMessage(Type.TEXT, "x", null, null, null);

		assertNull(m.media());
	}
}
