package fr.gouv.tchap.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.quarkus.lifecycle.TchapLifecycle;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Mode {@code password} avec le magasin mémoire (défaut) : à l'arrêt, le bot
 * ferme sa session avec le token obtenu à la connexion.
 */
class TchapLogoutOnShutdownTest {

	static final FakeHomeserver SERVER = FakeHomeserver.forTest(TchapLogoutOnShutdownTest.class,
			hs -> hs.login("at1", "rt1", 21_600_000).logout().join("!oncall:s", "!oncall:s")
					.firstSync(FakeHomeserver.syncWith(new String[]{"!oncall:s", "$e1", "hello"})));

	@RegisterExtension
	static final QuarkusExtensionTest TEST = new QuarkusExtensionTest().withApplicationRoot(jar -> jar
			.addClasses(FakeHomeserver.class)
			.addAsResource(new StringAsset("quarkus.tchap.homeserver-url=" + SERVER.url() + "\n"
					+ "quarkus.tchap.auth.mode=password\n" + "quarkus.tchap.user-id=@bot:s\n"
					+ "quarkus.tchap.auth.password=s3cret\n" + "quarkus.tchap.rooms.\"oncall\".room=!oncall:s\n"),
					"application.properties"));

	@Inject
	TchapLifecycle lifecycle;

	@AfterAll
	static void stopServer() {
		SERVER.close();
	}

	@Test
	void shutdown_closesTheSession_once() throws Exception {
		FakeHomeserver.await(() -> !SERVER.authorizations().isEmpty());
		assertTrue(SERVER.logouts().isEmpty(), "pas de logout tant que le bot tourne");

		lifecycle.onStop(null);
		lifecycle.onStop(null); // l'arrêt réel de Quarkus rappellera l'observer : idempotent

		assertEquals(1, SERVER.logouts().size());
		assertEquals("Bearer at1", SERVER.logouts().get(0));
		assertEquals(1, SERVER.logins().size(), "aucune nouvelle connexion pour se déconnecter");
	}
}
