package fr.gouv.tchap.quarkus.lifecycle;

import fr.gouv.tchap.matrix.client.MatrixClient;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class TchapLifecycle {

    private static final Logger LOG = Logger.getLogger(TchapLifecycle.class);
    private static final long START_TIMEOUT_SEC = 30;
    private static final long STOP_TIMEOUT_SEC  = 10;

    @Inject
    MatrixClient client;

    void onStart(@Observes StartupEvent event) {
        LOG.info("Starting Tchap bot");
        try {
            client.start().toCompletableFuture().get(START_TIMEOUT_SEC, TimeUnit.SECONDS);
            LOG.info("Tchap bot started");
        } catch (Exception e) {
            LOG.error("Failed to start Tchap bot", e);
            throw new IllegalStateException("Tchap bot failed to start", e);
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        LOG.info("Stopping Tchap bot");
        try {
            client.stop().toCompletableFuture().get(STOP_TIMEOUT_SEC, TimeUnit.SECONDS);
            LOG.info("Tchap bot stopped");
        } catch (Exception e) {
            LOG.warn("Error while stopping Tchap bot", e);
        }
    }
}
