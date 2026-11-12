package fr.gouv.tchap.quarkus;

import fr.gouv.tchap.matrix.auth.CredentialStore;
import fr.gouv.tchap.matrix.auth.TokenProvider;
import fr.gouv.tchap.matrix.client.MatrixClient;
import fr.gouv.tchap.matrix.core.auth.FileCredentialStore;
import fr.gouv.tchap.matrix.core.auth.InMemoryCredentialStore;
import fr.gouv.tchap.matrix.core.auth.PasswordTokenProvider;
import fr.gouv.tchap.matrix.core.auth.StaticTokenProvider;
import fr.gouv.tchap.matrix.core.client.DefaultMatrixClient;
import fr.gouv.tchap.matrix.core.client.MatrixClientOptions;
import fr.gouv.tchap.matrix.core.sync.FileSyncTokenStore;
import fr.gouv.tchap.matrix.core.sync.InMemorySyncTokenStore;
import fr.gouv.tchap.matrix.event.MatrixEvent;
import fr.gouv.tchap.matrix.sync.SyncTokenStore;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import fr.gouv.tchap.matrix.transport.http.VertxHttpTransport;
import fr.gouv.tchap.quarkus.config.TchapConfig;
import fr.gouv.tchap.quarkus.events.TchapEventPublisher;
import fr.gouv.tchap.quarkus.events.TchapMessageMapper;
import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.Startup;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

@ApplicationScoped
public class TchapClientProducer {

	private static final Logger LOG = Logger.getLogger(TchapClientProducer.class);

	/**
	 * Magasin du token de sync selon {@code quarkus.tchap.sync.store}.
	 * {@code @DefaultBean} : une application peut fournir son propre bean
	 * SyncTokenStore (base de données, etc.), qui remplace alors celui-ci.
	 */
	@Produces
	@Singleton
	@DefaultBean
	public SyncTokenStore syncTokenStore(TchapConfig config) {
		return switch (config.sync().store()) {
			case MEMORY -> new InMemorySyncTokenStore();
			case FILE -> new FileSyncTokenStore(Path.of(config.sync().storePath()));
		};
	}

	/**
	 * Magasin des identifiants (mode {@code password}) selon
	 * {@code quarkus.tchap.auth.store}. {@code @DefaultBean} : une application peut
	 * fournir son propre bean CredentialStore, qui remplace alors celui-ci.
	 */
	@Produces
	@Singleton
	@DefaultBean
	public CredentialStore credentialStore(TchapConfig config) {
		return switch (config.auth().store()) {
			case MEMORY -> new InMemoryCredentialStore();
			case FILE -> new FileCredentialStore(Path.of(config.auth().storePath()));
		};
	}

	@Produces
	@Singleton
	public HttpTransport httpTransport(TchapConfig config, Vertx vertx) {
		return new VertxHttpTransport(vertx, config.homeserverUrl().toString());
	}

	void disposeTransport(@Disposes HttpTransport transport) {
		transport.close();
	}

	@Produces
	@Singleton
	@Startup
	public MatrixClient matrixClient(TchapConfig config, HttpTransport httpTransport, SyncTokenStore tokenStore,
			CredentialStore credentialStore, TchapEventPublisher publisher) {
		String userId = config.userId().orElse(null);
		TokenProvider tokens;
		// Identifiant du bot pour ignorer ses propres messages : en mode password,
		// celui
		// que le serveur renvoie à la connexion (il peut différer de l'identifiant de
		// connexion configuré)
		Supplier<String> selfId;

		switch (config.auth().mode()) {
			case PASSWORD -> {
				String login = userId;
				String password = config.auth().password().orElse(null);
				if (login == null || password == null) {
					throw new IllegalStateException(
							"quarkus.tchap.user-id and quarkus.tchap.auth.password are required when "
									+ "quarkus.tchap.auth.mode=password");
				}
				PasswordTokenProvider provider = new PasswordTokenProvider(httpTransport, login, password,
						credentialStore, config.auth().deviceName());
				tokens = provider;
				selfId = () -> provider.serverUserId().orElse(login);
			}
			case TOKEN -> {
				String token = config.accessToken()
						.orElseThrow(() -> new IllegalStateException("quarkus.tchap.access-token is required "
								+ "(or set quarkus.tchap.auth.mode=password to log in with a password)"));
				tokens = new StaticTokenProvider(token);
				selfId = () -> userId;
			}
			default -> throw new IllegalStateException("Unsupported auth mode: " + config.auth().mode());
		}

		boolean ignoreSelf = config.bot().ignoreSelfMessages();
		if (ignoreSelf && userId == null) {
			LOG.warn("quarkus.tchap.user-id is not set: quarkus.tchap.bot.ignore-self-messages "
					+ "has no effect and the bot will receive its own messages");
		}

		TchapMessageMapper mapper = new TchapMessageMapper(ignoreSelf, selfId);
		Consumer<MatrixEvent> consumer = ev -> mapper.map(ev).ifPresent(publisher::publish);

		MatrixClientOptions options = new MatrixClientOptions(config.sync().initialLimit(), config.sync().timeout(),
				config.bot().autoJoinInvitedRooms());

		return new DefaultMatrixClient(httpTransport, tokens, userId, tokenStore, consumer, options);
	}
}
