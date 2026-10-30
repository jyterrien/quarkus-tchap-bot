package fr.gouv.tchap.examples.simple;

import fr.gouv.tchap.quarkus.TchapClient;
import fr.gouv.tchap.quarkus.events.TchapMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SimpleBot {

    private static final Logger LOG = Logger.getLogger(SimpleBot.class);

    @Inject
    TchapClient tchap;

    void onMessage(@Observes TchapMessage message) {
            LOG.infof("!ping received in room %s, replying pong", message.roomId());
        if ("!ping".equals(message.body())) {
            tchap.sendText(message.roomId(), "pong")
                .whenComplete((receipt, ex) -> {
                    if (ex != null) {
                        LOG.errorf("Failed to send pong: %s", ex.getMessage());
                    } else {
                        LOG.infof("pong sent (event_id=%s)", receipt.eventId());
                    }
                });
        }
    }
}
