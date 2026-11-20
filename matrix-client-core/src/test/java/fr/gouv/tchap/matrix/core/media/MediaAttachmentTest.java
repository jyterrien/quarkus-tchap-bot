package fr.gouv.tchap.matrix.core.media;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.matrix.media.MxcUri;
import fr.gouv.tchap.matrix.message.MediaAttachment;
import org.junit.jupiter.api.Test;

class MediaAttachmentTest {

	private static final MxcUri URI = new MxcUri("srv", "abc");

	@Test
	void of_setsUrlTypeAndSize_andNothingElse() {
		MediaAttachment m = MediaAttachment.of(URI, "image/png", 1234);

		assertEquals("mxc://srv/abc", m.url());
		assertEquals("image/png", m.mimeType());
		assertEquals(1234L, m.size());
		assertNull(m.width());
		assertNull(m.height());
		assertNull(m.durationMs());
		assertNull(m.filename());
		assertEquals(URI, m.uri());
	}

	@Test
	void withMethods_returnACopy() {
		MediaAttachment base = MediaAttachment.of(URI, "video/mp4", 10);

		MediaAttachment full = base.withDimensions(640, 480).withDuration(2500).withFilename("clip.mp4");

		assertEquals(640, full.width());
		assertEquals(480, full.height());
		assertEquals(2500L, full.durationMs());
		assertEquals("clip.mp4", full.filename());
		assertNull(base.width());
		assertNull(base.filename());
	}

	@Test
	void anInvalidUrl_isRefused() {
		assertThrows(IllegalArgumentException.class,
				() -> new MediaAttachment("http://srv/abc", null, null, null, null, null, null));
		assertThrows(IllegalArgumentException.class,
				() -> new MediaAttachment(null, null, null, null, null, null, null));
		assertThrows(IllegalArgumentException.class,
				() -> new MediaAttachment("mxc://srv/a/b", null, null, null, null, null, null));
	}

	@Test
	void metadata_isValidated() {
		assertThrows(IllegalArgumentException.class, () -> MediaAttachment.of(URI, " ", 1));
		assertThrows(IllegalArgumentException.class, () -> MediaAttachment.of(URI, "image/png", -1));
		assertThrows(IllegalArgumentException.class,
				() -> MediaAttachment.of(URI, "image/png", 1).withDimensions(0, 5));
		assertThrows(IllegalArgumentException.class,
				() -> MediaAttachment.of(URI, "image/png", 1).withDimensions(5, -1));
		assertThrows(IllegalArgumentException.class, () -> MediaAttachment.of(URI, "image/png", 1).withDuration(-1));
		assertThrows(IllegalArgumentException.class, () -> MediaAttachment.of(URI, "image/png", 1).withFilename(" "));
	}

	@Test
	void sizeZero_isAllowed() {
		assertEquals(0L, MediaAttachment.of(URI, "text/plain", 0).size());
	}
}
