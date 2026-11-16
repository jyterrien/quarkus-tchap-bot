package fr.gouv.tchap.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.quarkus.lifecycle.TchapLifecycle;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Magasin fichier avec {@code logout-on-shutdown=true} : la session est fermée
 * et le fichier d'identifiants supprimé (il ne vaut plus rien).
 */
class TchapLogoutWithFileStoreTest {

	static final Path CREDENTIALS = Path.of("target/tchap-logout-test-credentials.json").toAbsolutePath();

	static final FakeHomeserver SERVER = FakeHomeserver.forTest(TchapLogoutWithFileStoreTest.class, hs -> {
		try {
			Files.deleteIfExists(CREDENTIALS);
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
		hs.login("at1", "rt1", 21_600_000).logout().join("!oncall:s", "!oncall:s")
				.firstSync(FakeHomeserver.syncWith(new String[]{"!oncall:s", "$e1", "hello"}));
	});

	@RegisterExtension
	static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
			.withApplicationRoot(jar -> jar.addClasses(FakeHomeserver.class)
					.addAsResource(new StringAsset(
							"quarkus.tchap.homeserver-url=" + SERVER.url() + "\n" + "quarkus.tchap.auth.mode=password\n"
									+ "quarkus.tchap.user-id=@bot:s\n" + "quarkus.tchap.auth.password=s3cret\n"
									+ "quarkus.tchap.auth.store=file\n" + "quarkus.tchap.auth.store-path=" + CREDENTIALS
									+ "\n" + "quarkus.tchap.auth.logout-on-shutdown=true\n"
									+ "quarkus.tchap.rooms.\"oncall\".room=!oncall:s\n"),
							"application.properties"));

	@Inject
	TchapLifecycle lifecycle;

	@AfterAll
	static void stopServer() {
		SERVER.close();
	}

	@Test
	void shutdown_closesTheSession_andDeletesTheCredentialsFile() throws Exception {
		FakeHomeserver.await(() -> Files.exists(CREDENTIALS));

		lifecycle.onStop(null);

		assertEquals(1, SERVER.logouts().size());
		FakeHomeserver.await(() -> !Files.exists(CREDENTIALS));
	}
}
