package fr.gouv.tchap.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.quarkus.events.TchapMessage;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Le serveur refuse le token obtenu à la connexion (expiré) : le bot le
 * renouvelle avec son refresh token, sans se reconnecter, et rejoue la requête.
 */
class TchapTokenRenewalTest {

	static final FakeHomeserver SERVER = FakeHomeserver.forTest(TchapTokenRenewalTest.class,
			hs -> hs.login("at1", "rt1", 21_600_000).refresh("at2", "rt2", 21_600_000).join("!oncall:s", "!oncall:s")
					.firstSync(FakeHomeserver.syncWith(new String[]{"!oncall:s", "$e1", "hello"})).rejectToken("at1"));

	@RegisterExtension
	static final QuarkusExtensionTest TEST = new QuarkusExtensionTest().withApplicationRoot(jar -> jar
			.addClasses(App.class, FakeHomeserver.class)
			.addAsResource(new StringAsset("quarkus.tchap.homeserver-url=" + SERVER.url() + "\n"
					+ "quarkus.tchap.auth.mode=password\n" + "quarkus.tchap.user-id=@bot:s\n"
					+ "quarkus.tchap.auth.password=s3cret\n" + "quarkus.tchap.rooms.\"oncall\".room=!oncall:s\n"),
					"application.properties"));

	@Singleton
	static class App {
		final List<String> bodies = new CopyOnWriteArrayList<>();

		void onMessage(@Observes TchapMessage message) {
			bodies.add(message.body());
		}
	}

	@Inject
	App app;

	@AfterAll
	static void stopServer() {
		SERVER.close();
	}

	@Test
	void rejectedToken_isRenewedWithTheRefreshToken_andTheBotCarriesOn() throws Exception {
		FakeHomeserver.await(() -> !app.bodies.isEmpty());

		assertEquals(List.of("hello"), app.bodies);
		assertEquals(1, SERVER.logins().size(), "pas de reconnexion : le refresh token suffit");
		assertEquals(1, SERVER.refreshes().size());
		assertTrue(SERVER.refreshes().get(0).contains("\"refresh_token\":\"rt1\""), SERVER.refreshes().get(0));

		List<String> auth = SERVER.authorizations();
		assertEquals("Bearer at1", auth.get(0), "la première requête part avec le token de la connexion");
		assertTrue(auth.contains("Bearer at2"), auth.toString());
		assertEquals("Bearer at2", auth.get(auth.size() - 1), "ensuite, seul le nouveau token est utilisé");
	}
}
