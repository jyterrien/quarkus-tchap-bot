package fr.gouv.tchap.examples.simple;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import fr.gouv.tchap.quarkus.rooms.TchapRoom;
import fr.gouv.tchap.quarkus.rooms.TchapRoomName;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Smoke test CDI : vérifie que le contexte démarre et que le bot est lié au
 * salon d'intégration. Aucun appel réseau n'est effectué.
 */
@QuarkusTest
class SimpleBotTest {

	@Inject
	SimpleBot bot;

	@Inject
	@TchapRoomName("integration")
	TchapRoom integration;

	@Test
	void contextStarts() {
		assertNotNull(bot);
		assertNotNull(integration);
		assertEquals("integration", integration.name());
	}
}
