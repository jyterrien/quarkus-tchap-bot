package fr.gouv.tchap.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.quarkus.rooms.TchapRoom;
import fr.gouv.tchap.quarkus.rooms.TchapRoomName;
import io.quarkus.runtime.Startup;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Par défaut un salon est requis : s'il est inaccessible, l'application ne
 * démarre pas.
 */
class TchapRoomsRequiredFailureTest {

	static final FakeHomeserver SERVER = FakeHomeserver.forTest(TchapRoomsRequiredFailureTest.class,
			hs -> hs.firstSync("{\"next_batch\":\"s1\"}").joinForbidden("!gone:s"));

	@RegisterExtension
	static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
			.withApplicationRoot(jar -> jar.addClasses(Consumer.class, FakeHomeserver.class)
					.addAsResource(new StringAsset("quarkus.tchap.homeserver-url=" + SERVER.url() + "\n"
							+ "quarkus.tchap.access-token=tok\n" + "quarkus.tchap.rooms.\"gone\".room=!gone:s\n"),
							"application.properties"))
			.assertException(t -> {
				StringBuilder messages = new StringBuilder();
				for (Throwable c = t; c != null; c = c.getCause()) {
					messages.append(c.getMessage()).append('\n');
				}
				assertTrue(messages.toString().contains("Cannot join required Tchap room"), messages.toString());
				assertTrue(messages.toString().contains("M_FORBIDDEN"), messages.toString());
			});

	@Startup
	@Singleton
	static class Consumer {
		@Inject
		@TchapRoomName("gone")
		TchapRoom room;
	}

	@AfterAll
	static void stopServer() {
		SERVER.close();
	}

	@Test
	void applicationMustNotStart() {
		fail("L'application aurait dû échouer au démarrage");
	}
}
