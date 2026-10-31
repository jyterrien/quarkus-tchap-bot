package fr.gouv.tchap.quarkus.events;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import java.util.concurrent.RejectedExecutionException;

/**
 * Publie les messages Tchap sous forme d'événements CDI.
 *
 * Les observers sont invoqués sur le pool de workers de Quarkus et NON sur l'event loop
 * Vert.x qui exécute la boucle /sync : un observer bloquant (JDBC, appel HTTP synchrone,
 * sendText(...).join()) ne bloque donc ni le polling ni les autres événements.
 *
 * Conséquences : l'ordre de traitement entre deux messages n'est pas garanti
 * (ils peuvent s'exécuter en parallèle), et une exception d'observer est journalisée ici
 * sans affecter la boucle de synchronisation.
 */
@ApplicationScoped
public class TchapEventPublisher {

    private static final Logger LOG = Logger.getLogger(TchapEventPublisher.class);

    @Inject
    Event<TchapMessage> messageEvent;

    @Inject
    ManagedExecutor executor;

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
            messageEvent.fire(message);
        } catch (Exception e) {
            LOG.errorf(e, "Observer failed for message %s in room %s",
                message.eventId(), message.roomId());
        }
    }
}
