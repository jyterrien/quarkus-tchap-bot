package fr.gouv.tchap.examples.simple;

import fr.gouv.tchap.quarkus.events.TchapMessage;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Smoke test CDI : vérifie que le contexte démarre et que les beans
 * attendus sont injectables. Aucun appel réseau n'est effectué.
 * Le comportement de !ping est testé séparément avec un mock de TchapClient.
 */
@QuarkusTest
class SimpleBotTest {

    @Inject
    Event<TchapMessage> messageEvent;

    @Inject
    SimpleBot bot;

    @Test
    void contextStarts() {
        assertNotNull(messageEvent);
        assertNotNull(bot);
    }
}
