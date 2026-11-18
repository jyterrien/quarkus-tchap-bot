package fr.gouv.tchap.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.quarkus.TchapClient;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.concurrent.CompletionException;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Un homeserver qui ne répond pas : l'envoi échoue au bout de
 * {@code quarkus.tchap.http.request-timeout} au lieu de bloquer indéfiniment.
 */
class TchapRequestTimeoutTest {

	static final FakeHomeserver SERVER = FakeHomeserver.forTest(TchapRequestTimeoutTest.class,
			hs -> hs.join("!oncall:s", "!oncall:s").slowSends(8_000));

	@RegisterExtension
	static final QuarkusExtensionTest TEST = new QuarkusExtensionTest().withApplicationRoot(jar -> jar
			.addClasses(FakeHomeserver.class)
			.addAsResource(new StringAsset("quarkus.tchap.homeserver-url=" + SERVER.url() + "\n"
					+ "quarkus.tchap.access-token=tok\n" + "quarkus.tchap.sync.enabled=false\n"
					+ "quarkus.tchap.http.request-timeout=1S\n" + "quarkus.tchap.rooms.\"oncall\".room=!oncall:s\n"),
					"application.properties"));

	@Inject
	TchapClient client;

	@AfterAll
	static void stopServer() {
		SERVER.close();
	}

	@Test
	void sendToASilentHomeserver_failsAfterTheConfiguredTimeout() {
		long start = System.nanoTime();

		CompletionException ex = assertThrows(CompletionException.class,
				() -> client.sendText("!oncall:s", "bonjour").toCompletableFuture().join());
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		// Classe comparée par son nom : elle vient du classloader de l'application
		assertEquals("MatrixTransportException", ex.getCause().getClass().getSimpleName());
		assertTrue(ex.getCause().getMessage().contains("timed out"), ex.getCause().getMessage());
		assertTrue(elapsedMs < 6_000, "l'échec doit venir du délai de 1 s, pas des 8 s du serveur : " + elapsedMs);
	}
}
