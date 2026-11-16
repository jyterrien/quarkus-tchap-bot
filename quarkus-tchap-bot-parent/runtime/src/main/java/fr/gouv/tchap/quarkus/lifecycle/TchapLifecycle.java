package fr.gouv.tchap.quarkus.lifecycle;

import fr.gouv.tchap.matrix.auth.CredentialStore;
import fr.gouv.tchap.matrix.client.MatrixClient;
import fr.gouv.tchap.matrix.core.auth.InMemoryCredentialStore;
import fr.gouv.tchap.quarkus.config.TchapConfig;
import fr.gouv.tchap.quarkus.rooms.TchapRooms;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

@ApplicationScoped
public class TchapLifecycle {

	private static final Logger LOG = Logger.getLogger(TchapLifecycle.class);
	private static final long START_TIMEOUT_SEC = 30;
	private static final long STOP_TIMEOUT_SEC = 10;
	private static final long LOGOUT_TIMEOUT_SEC = 5;

	@Inject
	MatrixClient client;

	@Inject
	TchapConfig config;

	@Inject
	TchapRooms rooms;

	@Inject
	CredentialStore credentials;

	private volatile boolean syncStarted;

	void onStart(@Observes StartupEvent event) {
		// Les salons nommés sont rejoints même si la synchronisation est désactivée :
		// on veut pouvoir y écrire
		boolean anyRoomAvailable = rooms.initialize();

		if (!config.sync().enabled()) {
			LOG.info("Tchap sync disabled (quarkus.tchap.sync.enabled=false): "
					+ "the bot can send messages but will not receive any");
			return;
		}
		if (!anyRoomAvailable) {
			LOG.warn("None of the configured Tchap rooms is available: sync not started");
			return;
		}
		LOG.info("Starting Tchap bot");
		try {
			client.start().toCompletableFuture().get(START_TIMEOUT_SEC, TimeUnit.SECONDS);
			syncStarted = true;
			LOG.info("Tchap bot started");
		} catch (Exception e) {
			LOG.error("Failed to start Tchap bot", e);
			throw new IllegalStateException("Tchap bot failed to start", e);
		}
	}

	/**
	 * Public : l'observer est appelé aussi directement par les tests. Idempotent.
	 */
	public void onStop(@Observes ShutdownEvent event) {
		if (syncStarted) {
			LOG.info("Stopping Tchap bot");
			try {
				client.stop().toCompletableFuture().get(STOP_TIMEOUT_SEC, TimeUnit.SECONDS);
				LOG.info("Tchap bot stopped");
			} catch (Exception e) {
				LOG.warn("Error while stopping Tchap bot", e);
			}
		}
		// Même sans synchronisation : le bot a pu se connecter pour envoyer des
		// messages
		if (shouldLogout(config.auth().mode(), config.auth().logoutOnShutdown(), credentials)) {
			logout();
		}
	}

	/**
	 * Faut-il fermer la session à l'arrêt ? Jamais en mode {@code token} (la
	 * session n'est pas celle du bot). En mode {@code password} : le réglage
	 * explicite, sinon oui quand les identifiants ne sont pas persistés par
	 * l'extension (magasin mémoire : la session ne serait pas reprise de toute
	 * façon), non quand ils le sont ou que l'application fournit son propre
	 * magasin.
	 */
	static boolean shouldLogout(TchapConfig.AuthConfig.Mode mode, Optional<Boolean> configured, CredentialStore store) {
		if (mode != TchapConfig.AuthConfig.Mode.PASSWORD) {
			return false;
		}
		return configured.orElseGet(() -> store instanceof InMemoryCredentialStore);
	}

	private void logout() {
		try {
			client.logout().toCompletableFuture().get(LOGOUT_TIMEOUT_SEC, TimeUnit.SECONDS);
		} catch (Exception e) {
			// La session expirera d'elle-même : ne jamais empêcher l'arrêt
			LOG.warnf("Could not close the Tchap session (it will expire on its own): %s", e.getMessage());
		}
	}
}
