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
 * Publie les événements Tchap ({@link TchapMessage},
 * {@link TchapMessageEdited}, {@link TchapReaction}) sous forme d'événements
 * CDI. Un événement d'un salon nommé est publié avec le qualificatif
 * {@link TchapRoomName} de ce salon : il atteint les observers qualifiés pour
 * ce salon ET les observers sans qualificatif. Un message d'un salon non nommé
 * n'atteint que les observers sans qualificatif. Les observers sont invoqués
 * sur le pool de workers de Quarkus et NON sur l'event loop Vert.x qui exécute
 * la boucle /sync : un observer bloquant ne bloque donc ni le polling ni les
 * autres événements. L'ordre de traitement entre deux messages n'est pas
 * garanti.
 */
@ApplicationScoped
public class TchapEventPublisher {

	private static final Logger LOG = Logger.getLogger(TchapEventPublisher.class);

	@Inject
	Event<TchapMessage> messageEvent;

	@Inject
	Event<TchapMessageEdited> editEvent;

	@Inject
	Event<TchapReaction> reactionEvent;

	@Inject
	ManagedExecutor executor;

	@Inject
	TchapRooms rooms;

	public void publish(TchapMessage message) {
		dispatch(messageEvent, message, message.roomId(), message.eventId());
	}

	public void publish(TchapMessageEdited edit) {
		dispatch(editEvent, edit, edit.roomId(), edit.eventId());
	}

	public void publish(TchapReaction reaction) {
		dispatch(reactionEvent, reaction, reaction.roomId(), reaction.eventId());
	}

	private <T> void dispatch(Event<T> base, T payload, String roomId, String eventId) {
		try {
			executor.execute(() -> fire(base, payload, roomId, eventId));
		} catch (RejectedExecutionException e) {
			// Arrêt de l'application en cours : le pool n'accepte plus de tâches
			LOG.warnf("Event %s dropped: executor is shutting down", eventId);
		}
	}

	private <T> void fire(Event<T> base, T payload, String roomId, String eventId) {
		try {
			Event<T> event = rooms.nameOf(roomId).map(name -> base.select(new TchapRoomName.Literal(name)))
					.orElse(base);
			event.fire(payload);
		} catch (Exception e) {
			LOG.errorf(e, "Observer failed for event %s in room %s", eventId, roomId);
		}
	}
}
