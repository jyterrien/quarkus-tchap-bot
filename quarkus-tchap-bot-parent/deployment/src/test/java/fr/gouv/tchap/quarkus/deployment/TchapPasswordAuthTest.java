package fr.gouv.tchap.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.quarkus.events.TchapMessage;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Mode {@code password} : le bot se connecte seul au démarrage (une fois, en
 * demandant un refresh token) puis utilise le token obtenu pour toutes ses
 * requêtes.
 */
class TchapPasswordAuthTest {

	static final FakeHomeserver SERVER = FakeHomeserver.forTest(TchapPasswordAuthTest.class,
			hs -> hs.login("at1", "rt1", 21_600_000).join("!oncall:s", "!oncall:s")
					.firstSync(FakeHomeserver.syncWith(new String[]{"!oncall:s", "$e1", "hello"})));

	@RegisterExtension
	static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
			.withApplicationRoot(jar -> jar.addClasses(App.class, FakeHomeserver.class)
					.addAsResource(new StringAsset("quarkus.tchap.homeserver-url=" + SERVER.url() + "\n"
							+ "quarkus.tchap.auth.mode=password\n" + "quarkus.tchap.user-id=@bot.fr:s\n"
							+ "quarkus.tchap.auth.password=s3cret\n" + "quarkus.tchap.auth.device-name=test-bot\n"
							+ "quarkus.tchap.rooms.\"oncall\".room=!oncall:s\n"), "application.properties"));

	@Singleton
	static class App {
		final List<String> bodies = new CopyOnWriteArrayList<>();

		void onMessage(@Observes TchapMessage message) {
			bodies.add(message.body());
		}
	}

	@jakarta.inject.Inject
	App app;

	@AfterAll
	static void stopServer() {
		SERVER.close();
	}

	@Test
	void theBotLogsInOnce_andUsesTheTokenEverywhere() throws Exception {
		FakeHomeserver.await(() -> !app.bodies.isEmpty());

		assertEquals(List.of("hello"), app.bodies);
		assertEquals(1, SERVER.logins().size(), "une seule connexion");
		String login = SERVER.logins().get(0);
		assertTrue(login.contains("\"user\":\"@bot.fr:s\""), login);
		assertTrue(login.contains("\"password\":\"s3cret\""), login);
		assertTrue(login.contains("\"refresh_token\":true"), login);
		assertTrue(login.contains("\"initial_device_display_name\":\"test-bot\""), login);

		assertFalse(SERVER.authorizations().isEmpty());
		assertTrue(SERVER.authorizations().stream().allMatch("Bearer at1"::equals), SERVER.authorizations().toString());
		assertTrue(SERVER.refreshes().isEmpty());
	}
}
