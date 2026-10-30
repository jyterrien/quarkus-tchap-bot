package fr.gouv.tchap.examples.command;

import fr.gouv.tchap.quarkus.TchapClient;
import fr.gouv.tchap.quarkus.events.TchapMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class CommandBot {

    @Inject
    TchapClient tchap;

    void onMessage(@Observes TchapMessage message) {
        String body = message.body();
        if (body != null && body.startsWith("!help")) {
            tchap.sendText(message.roomId(), "Commandes : !help, !ping");
        }
    }
}
