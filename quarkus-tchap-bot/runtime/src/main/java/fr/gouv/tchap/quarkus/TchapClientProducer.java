package fr.gouv.tchap.quarkus;

import fr.gouv.tchap.matrix.client.MatrixClient;
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
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.function.Consumer;

@ApplicationScoped
public class TchapClientProducer {

    private static final Logger LOG = Logger.getLogger(TchapClientProducer.class);

    /**
     * Magasin du token de sync selon {@code quarkus.tchap.sync.store}.
     * {@code @DefaultBean} : une application peut fournir son propre bean SyncTokenStore
     * (base de données, etc.), qui remplace alors celui-ci.
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
    public MatrixClient matrixClient(TchapConfig config,
                                     HttpTransport httpTransport,
                                     SyncTokenStore tokenStore,
                                     TchapEventPublisher publisher) {
        String token = config.accessToken()
            .orElseThrow(() -> new IllegalStateException("quarkus.tchap.access-token is required"));
        String userId = config.userId().orElse(null);

        boolean ignoreSelf = config.bot().ignoreSelfMessages();
        if (ignoreSelf && userId == null) {
            LOG.warn("quarkus.tchap.user-id is not set: quarkus.tchap.bot.ignore-self-messages "
                + "has no effect and the bot will receive its own messages");
        }

        TchapMessageMapper mapper = new TchapMessageMapper(ignoreSelf, userId);
        Consumer<MatrixEvent> consumer = ev -> mapper.map(ev).ifPresent(publisher::publish);

        MatrixClientOptions options = new MatrixClientOptions(
            config.sync().initialLimit(),
            config.sync().timeout(),
            config.bot().autoJoinInvitedRooms());

        return new DefaultMatrixClient(httpTransport, token, userId, tokenStore, consumer, options);
    }
}
