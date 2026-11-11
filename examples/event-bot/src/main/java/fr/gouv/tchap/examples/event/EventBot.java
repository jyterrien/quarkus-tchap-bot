package fr.gouv.tchap.examples.event;

import fr.gouv.tchap.quarkus.events.TchapMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

@ApplicationScoped
public class EventBot {

	private static final Logger LOG = Logger.getLogger(EventBot.class);

	void onMessage(@Observes TchapMessage message) {
		LOG.infof("Event reçu : room=%s sender=%s type=%s thread=%s", message.roomId(), message.sender(),
				message.messageType(), message.isInThread() ? message.threadRootId() : "-");
	}
}
