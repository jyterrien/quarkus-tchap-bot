package fr.gouv.tchap.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.QuarkusExtensionTest;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Mode password sans mot de passe : message explicite au démarrage. */
class TchapPasswordMissingConfigTest {

	@RegisterExtension
	static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
			.withApplicationRoot(jar -> jar.addAsResource(
					new StringAsset("quarkus.tchap.homeserver-url=http://127.0.0.1:1\n"
							+ "quarkus.tchap.auth.mode=password\n" + "quarkus.tchap.user-id=@bot:s\n"),
					"application.properties"))
			.assertException(t -> {
				StringBuilder messages = new StringBuilder();
				for (Throwable c = t; c != null; c = c.getCause()) {
					messages.append(c.getMessage()).append('\n');
				}
				assertTrue(messages.toString().contains("quarkus.tchap.auth.password"), messages.toString());
			});

	@Test
	void startupFails() {
		// Vérifié par assertException ci-dessus
	}
}
