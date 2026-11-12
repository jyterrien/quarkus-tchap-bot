package fr.gouv.tchap.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.QuarkusExtensionTest;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Mauvais mot de passe : le démarrage échoue avec un message explicite, sans
 * réessayer en boucle, et sans jamais afficher le mot de passe.
 */
class TchapLoginRefusedTest {

	static final FakeHomeserver SERVER = FakeHomeserver.forTest(TchapLoginRefusedTest.class,
			hs -> hs.loginForbidden().join("!oncall:s", "!oncall:s"));

	@RegisterExtension
	static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
			.withApplicationRoot(jar -> jar.addClasses(FakeHomeserver.class)
					.addAsResource(new StringAsset(
							"quarkus.tchap.homeserver-url=" + SERVER.url() + "\n" + "quarkus.tchap.auth.mode=password\n"
									+ "quarkus.tchap.user-id=@bot:s\n" + "quarkus.tchap.auth.password=wrong-pass\n"
									+ "quarkus.tchap.rooms.\"oncall\".room=!oncall:s\n"),
							"application.properties"))
			.assertException(t -> {
				StringBuilder messages = new StringBuilder();
				for (Throwable c = t; c != null; c = c.getCause()) {
					messages.append(c.getMessage()).append('\n');
				}
				assertTrue(messages.toString().contains("Login refused"), messages.toString());
				assertFalse(messages.toString().contains("wrong-pass"), "le mot de passe ne doit jamais être affiché");
			});

	@AfterAll
	static void stopServer() {
		SERVER.close();
	}

	@Test
	void startupFails() {
		// Vérifié par assertException ci-dessus
	}
}
