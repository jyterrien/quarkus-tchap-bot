package fr.gouv.tchap.quarkus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.gouv.tchap.matrix.client.MatrixClient;
import fr.gouv.tchap.matrix.core.client.DefaultMatrixClient;
import fr.gouv.tchap.matrix.core.sync.InMemorySyncTokenStore;
import fr.gouv.tchap.matrix.event.MatrixEvent;
import fr.gouv.tchap.matrix.sync.SyncTokenStore;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import fr.gouv.tchap.matrix.transport.http.VertxHttpTransport;
import fr.gouv.tchap.quarkus.config.TchapConfig;
import fr.gouv.tchap.quarkus.events.TchapEventPublisher;
import io.quarkus.runtime.Startup;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.util.function.Consumer;

@ApplicationScoped
public class TchapClientProducer {

    private static final Logger LOG = Logger.getLogger(TchapClientProducer.class);

    @Produces
    @Singleton
    public SyncTokenStore syncTokenStore() {
        return new InMemorySyncTokenStore();
    }

    @Produces
    @Singleton
    public HttpTransport httpTransport(TchapConfig config, Vertx vertx) {
        return new VertxHttpTransport(vertx, config.homeserverUrl().toString());
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

        Consumer<MatrixEvent> consumer = ev -> publish(publisher, ev, config.bot().ignoreSelfMessages(), userId);

        return new DefaultMatrixClient(
            httpTransport,
            token,
            userId,
            tokenStore,
            consumer,
            config.sync().initialLimit()
        );
    }

    void dispose(@Disposes MatrixClient client) {
        try {
            client.stop().toCompletableFuture().get();
        } catch (Exception e) {
            LOG.warn("Error while stopping MatrixClient", e);
        }
    }

    private void publish(TchapEventPublisher publisher, MatrixEvent ev, boolean ignoreSelf, String botUserId) {
        LOG.infof("RAW EVENT: type=%s room=%s sender=%s", ev.type(), ev.roomId(), ev.sender());
        if (ignoreSelf && botUserId != null && botUserId.equals(ev.sender())) return;
        if (!"m.room.message".equals(ev.type())) return;
        try {
            JsonNode content = new ObjectMapper().readTree(ev.content());
            String body = content.path("body").asText("");
            String msgtype = content.path("msgtype").asText("m.text");
            publisher.publish(new fr.gouv.tchap.quarkus.events.TchapMessage(
                ev.eventId(), ev.roomId(), ev.sender(), body, ev.timestamp(), false, msgtype
            ));
        } catch (Exception e) {
            LOG.warnf("Cannot parse m.room.message content for event %s: %s", ev.eventId(), e.getMessage());
        }
    }
}
