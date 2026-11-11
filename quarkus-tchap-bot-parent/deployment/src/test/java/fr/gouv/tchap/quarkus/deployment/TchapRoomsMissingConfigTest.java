package fr.gouv.tchap.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.quarkus.rooms.TchapRoom;
import fr.gouv.tchap.quarkus.rooms.TchapRoomName;
import io.quarkus.runtime.Startup;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Un nom injecté mais absent de la configuration doit faire échouer le
 * démarrage, avec un message explicite.
 */
class TchapRoomsMissingConfigTest {

	@RegisterExtension
	static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
			.withApplicationRoot(
					jar -> jar.addClasses(Consumer.class)
							.addAsResource(new StringAsset("quarkus.tchap.homeserver-url=http://127.0.0.1:1\n"
									+ "quarkus.tchap.access-token=tok\n"), "application.properties"))
			.assertException(t -> {
				StringBuilder messages = new StringBuilder();
				for (Throwable c = t; c != null; c = c.getCause()) {
					messages.append(c.getMessage()).append('\n');
				}
				assertTrue(messages.toString().contains("quarkus.tchap.rooms.\"missing\".room"), messages.toString());
			});

	@Startup
	@Singleton
	static class Consumer {
		@Inject
		@TchapRoomName("missing")
		TchapRoom room;
	}

	@Test
	void applicationMustNotStart() {
		fail("L'application aurait dû échouer au démarrage");
	}
}
