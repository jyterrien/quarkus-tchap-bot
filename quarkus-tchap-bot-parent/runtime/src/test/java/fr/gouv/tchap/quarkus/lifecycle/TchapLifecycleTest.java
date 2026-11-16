package fr.gouv.tchap.quarkus.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.matrix.auth.CredentialStore;
import fr.gouv.tchap.matrix.core.auth.FileCredentialStore;
import fr.gouv.tchap.matrix.core.auth.InMemoryCredentialStore;
import fr.gouv.tchap.quarkus.config.TchapConfig.AuthConfig.Mode;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Faut-il fermer la session à l'arrêt ? */
class TchapLifecycleTest {

	private static final CredentialStore MEMORY = new InMemoryCredentialStore();
	private static final CredentialStore FILE = new FileCredentialStore(Path.of("target/unused-credentials.json"));
	/** Magasin fourni par l'application (base de données, etc.). */
	private static final CredentialStore CUSTOM = new CredentialStore() {
		@Override
		public java.util.concurrent.CompletionStage<Optional<fr.gouv.tchap.matrix.auth.Credentials>> load() {
			return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
		}

		@Override
		public java.util.concurrent.CompletionStage<Void> save(fr.gouv.tchap.matrix.auth.Credentials credentials) {
			return java.util.concurrent.CompletableFuture.completedFuture(null);
		}
	};

	@Test
	void tokenMode_neverLogsOut_whateverTheSetting() {
		assertFalse(TchapLifecycle.shouldLogout(Mode.TOKEN, Optional.empty(), MEMORY));
		assertFalse(TchapLifecycle.shouldLogout(Mode.TOKEN, Optional.of(true), MEMORY));
		assertFalse(TchapLifecycle.shouldLogout(Mode.TOKEN, Optional.of(true), FILE));
	}

	@Test
	void passwordMode_withTheMemoryStore_logsOutByDefault() {
		assertTrue(TchapLifecycle.shouldLogout(Mode.PASSWORD, Optional.empty(), MEMORY));
	}

	@Test
	void passwordMode_withAPersistentStore_keepsTheSessionByDefault() {
		assertFalse(TchapLifecycle.shouldLogout(Mode.PASSWORD, Optional.empty(), FILE));
		assertFalse(TchapLifecycle.shouldLogout(Mode.PASSWORD, Optional.empty(), CUSTOM));
	}

	@Test
	void anExplicitSetting_alwaysWins_inPasswordMode() {
		assertFalse(TchapLifecycle.shouldLogout(Mode.PASSWORD, Optional.of(false), MEMORY));
		assertTrue(TchapLifecycle.shouldLogout(Mode.PASSWORD, Optional.of(true), FILE));
		assertTrue(TchapLifecycle.shouldLogout(Mode.PASSWORD, Optional.of(true), CUSTOM));
	}
}
