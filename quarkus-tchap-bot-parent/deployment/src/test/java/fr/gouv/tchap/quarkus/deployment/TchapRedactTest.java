package fr.gouv.tchap.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.matrix.message.MessageReceipt;
import fr.gouv.tchap.quarkus.TchapClient;
import fr.gouv.tchap.quarkus.rooms.TchapRoom;
import fr.gouv.tchap.quarkus.rooms.TchapRoomName;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.concurrent.CompletionException;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Supprimer un message, retirer une réaction : de bout en bout, vers le bon
 * salon, avec le motif et l'identifiant de l'événement visé.
 */
class TchapRedactTest {

	static final FakeHomeserver SERVER = FakeHomeserver.forTest(TchapRedactTest.class,
			hs -> hs.join("!oncall:s", "!oncall:s").redactOk().redactForbidden("notmine"));

	@RegisterExtension
	static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
			.withApplicationRoot(jar -> jar.addClasses(App.class, FakeHomeserver.class)
					.addAsResource(new StringAsset("quarkus.tchap.homeserver-url=" + SERVER.url() + "\n"
							+ "quarkus.tchap.access-token=tok\n" + "quarkus.tchap.sync.enabled=false\n"
							+ "quarkus.tchap.rooms.\"oncall\".room=!oncall:s\n"), "application.properties"));

	@Singleton
	static class App {
		@Inject
		@TchapRoomName("oncall")
		TchapRoom oncall;

		@Inject
		TchapClient client;
	}

	@Inject
	App app;

	@AfterAll
	static void stopServer() {
		SERVER.close();
	}

	private static String redactContaining(String text) throws Exception {
		FakeHomeserver.await(() -> SERVER.redacts().stream().anyMatch(s -> s.contains(text)));
		return SERVER.redacts().stream().filter(s -> s.contains(text)).findFirst().orElseThrow();
	}

	@Test
	void roomHandle_redactsAMessage_withTheReason() throws Exception {
		MessageReceipt receipt = app.oncall.redact("$orig1", "message envoyé par erreur").toCompletableFuture().join();

		assertEquals("$redaction", receipt.eventId(), "le reçu est celui de l'événement de suppression");
		String redact = redactContaining("message envoyé par erreur");
		assertTrue(redact.contains("/rooms/!oncall:s/redact/$orig1/"), redact);
		assertTrue(redact.contains("\"reason\":\"message envoyé par erreur\""), redact);
	}

	@Test
	void redactWithoutReason_sendsAnEmptyBody() throws Exception {
		app.oncall.redact("$orig2", null).toCompletableFuture().join();

		String redact = redactContaining("/redact/$orig2/");
		assertTrue(redact.endsWith("{}"), redact);
	}

	@Test
	void aReaction_isRemovedByRedactingItsEvent() throws Exception {
		MessageReceipt reaction = app.oncall.react("$orig3", "👍").toCompletableFuture().join();

		app.oncall.redact(reaction.eventId(), null).toCompletableFuture().join();

		String redact = redactContaining("/redact/" + reaction.eventId() + "/");
		assertTrue(redact.contains("/rooms/!oncall:s/redact/"), redact);
	}

	@Test
	void client_redacts_inTheGivenRoom() throws Exception {
		app.client.redact("!oncall:s", "$orig4", "doublon").toCompletableFuture().join();

		String redact = redactContaining("doublon");
		assertTrue(redact.contains("/rooms/!oncall:s/redact/$orig4/"), redact);
	}

	@Test
	void withoutTheModerationRight_theServerRefusal_isPropagated() {
		CompletionException ex = assertThrows(CompletionException.class,
				() -> app.oncall.redact("$notmine1", null).toCompletableFuture().join());

		assertEquals("MatrixServerException", ex.getCause().getClass().getSimpleName());
		assertTrue(ex.getCause().getMessage().contains("M_FORBIDDEN"), ex.getCause().getMessage());
	}

	@Test
	void aBlankEventId_isRejected() {
		assertThrows(IllegalArgumentException.class, () -> app.oncall.redact(" ", null));
		assertThrows(IllegalArgumentException.class, () -> app.client.redact("!oncall:s", null, null));
	}
}
