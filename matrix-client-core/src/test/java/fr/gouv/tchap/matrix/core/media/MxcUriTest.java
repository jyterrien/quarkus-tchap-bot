package fr.gouv.tchap.matrix.core.media;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.matrix.media.MxcUri;
import org.junit.jupiter.api.Test;

class MxcUriTest {

	@Test
	void parse_readsServerAndMediaId() {
		MxcUri uri = MxcUri.parse("mxc://agent.social.tchap.gouv.fr/AbC123_-x");

		assertEquals("agent.social.tchap.gouv.fr", uri.serverName());
		assertEquals("AbC123_-x", uri.mediaId());
		assertEquals("mxc://agent.social.tchap.gouv.fr/AbC123_-x", uri.toString());
	}

	@Test
	void parse_acceptsAPortAnAnIpv6Server() {
		assertEquals("matrix.example.org:8448", MxcUri.parse("mxc://matrix.example.org:8448/id1").serverName());
		assertEquals("[::1]:8008", MxcUri.parse("mxc://[::1]:8008/id1").serverName());
	}

	@Test
	void parse_rejectsAnythingThatIsNotExactlyMxcServerId() {
		for (String bad : new String[]{null, "", "http://srv/id", "mxc:/srv/id", "mxc://", "mxc://srv", "mxc://srv/",
				"mxc:///id", "mxc://srv/id/extra", "mxc://srv/id?x=1", "mxc://srv/id#frag", "mxc://srv/a b",
				"mxc://srv/a%2Fb", "mxc://srv/../etc", "mxc://srv/..", "mxc://srv/.", "mxc://../id", "MXC://srv/id"}) {
			assertThrows(IllegalArgumentException.class, () -> MxcUri.parse(bad), "devrait être refusé : " + bad);
			assertFalse(MxcUri.isValid(bad), String.valueOf(bad));
		}
	}

	@Test
	void isValid_acceptsWellFormedUris() {
		assertTrue(MxcUri.isValid("mxc://srv/abc"));
	}

	@Test
	void constructor_validatesBothParts_soNothingUnsafeReachesAUrlPath() {
		assertThrows(IllegalArgumentException.class, () -> new MxcUri("srv/evil", "id"));
		assertThrows(IllegalArgumentException.class, () -> new MxcUri("srv", "../id"));
		assertThrows(IllegalArgumentException.class, () -> new MxcUri("srv", "a/b"));
		assertThrows(IllegalArgumentException.class, () -> new MxcUri(null, "id"));
		assertThrows(IllegalArgumentException.class, () -> new MxcUri("srv", null));
		assertThrows(IllegalArgumentException.class, () -> new MxcUri("srv", ""));
	}

	@Test
	void roundTrip_parseOfToString_isTheSameUri() {
		MxcUri uri = new MxcUri("srv.example", "x_Y-1.2=3");

		assertEquals(uri, MxcUri.parse(uri.toString()));
	}
}
