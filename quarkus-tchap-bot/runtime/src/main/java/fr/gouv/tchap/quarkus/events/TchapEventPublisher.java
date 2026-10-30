package fr.gouv.tchap.quarkus.events;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

@ApplicationScoped
public class TchapEventPublisher {

    @Inject
    Event<TchapMessage> messageEvent;

    public void publish(TchapMessage message) {
        messageEvent.fire(message);
    }
}
