package fr.gouv.tchap.examples.simple;

import fr.gouv.tchap.quarkus.events.TchapMessage;
import fr.gouv.tchap.quarkus.rooms.TchapRoom;
import fr.gouv.tchap.quarkus.rooms.TchapRoomName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Bot « ping/pong » confiné au salon d'intégration Tchap.
 *
 * <p>Le salon est déclaré sous {@code quarkus.tchap.rooms."integration"} dans
 * {@code application.properties} ; l'instance {@link TchapRoom} injectée est
 * liée à ce salon et ne peut pas écrire ailleurs.
 */
@ApplicationScoped
public class SimpleBot {

    private static final Logger LOG = Logger.getLogger(SimpleBot.class);

    /** Handle du salon d'intégration : tout ce qui passe par lui va dans ce salon. */
    @Inject
    @TchapRoomName("integration")
    TchapRoom integration;

    void onMessage(@Observes @TchapRoomName("integration") TchapMessage message) {
        // Ne réagit qu'aux messages de CE salon, jamais d'un autre.
        LOG.infof("!ping received in room %s, replying pong", message.roomId());
        if ("!ping".equals(message.body())) {
            integration.sendText("pong")
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