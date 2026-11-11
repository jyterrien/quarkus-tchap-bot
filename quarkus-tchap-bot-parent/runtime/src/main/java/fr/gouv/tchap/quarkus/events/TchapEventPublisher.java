package fr.gouv.tchap.quarkus.events;

import fr.gouv.tchap.quarkus.rooms.TchapRoomName;
import fr.gouv.tchap.quarkus.rooms.TchapRooms;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.util.concurrent.RejectedExecutionException;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

/**
 * Publie les messages Tchap sous forme d'événements CDI. Un message d'un salon
 * nommé est publié avec le qualificatif {@link TchapRoomName} de ce salon : il
 * atteint les observers qualifiés pour ce salon ET les observers sans
 * qualificatif. Un message d'un salon non nommé n'atteint que les observers
 * sans qualificatif. Les observers sont invoqués sur le pool de workers de
 * Quarkus et NON sur l'event loop Vert.x qui exécute la boucle /sync : un
 * observer bloquant ne bloque donc ni le polling ni les autres événements.
 * L'ordre de traitement entre deux messages n'est pas garanti.
 */
@ApplicationScoped
public class TchapEventPublisher {

	private static final Logger LOG = Logger.getLogger(TchapEventPublisher.class);

	@Inject
	Event<TchapMessage> messageEvent;

	@Inject
	ManagedExecutor executor;

	@Inject
	TchapRooms rooms;

	public void publish(TchapMessage message) {
		try {
			executor.execute(() -> fire(message));
		} catch (RejectedExecutionException e) {
			// Arrêt de l'application en cours : le pool n'accepte plus de tâches
			LOG.warnf("Message %s dropped: executor is shutting down", message.eventId());
		}
	}

	private void fire(TchapMessage message) {
		try {
			Event<TchapMessage> event = rooms.nameOf(message.roomId())
					.map(name -> messageEvent.select(new TchapRoomName.Literal(name))).orElse(messageEvent);
			event.fire(message);
		} catch (Exception e) {
			LOG.errorf(e, "Observer failed for message %s in room %s", message.eventId(), message.roomId());
		}
	}
}
