package fr.gouv.tchap.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.gouv.tchap.matrix.message.OutgoingMessage;
import fr.gouv.tchap.quarkus.TchapClient;
import fr.gouv.tchap.quarkus.events.TchapMessage;
import fr.gouv.tchap.quarkus.events.TchapMessageEdited;
import fr.gouv.tchap.quarkus.events.TchapReaction;
import fr.gouv.tchap.quarkus.rooms.TchapRoom;
import fr.gouv.tchap.quarkus.rooms.TchapRoomName;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Éditions et réactions de bout en bout : reçues, elles arrivent comme des
 * événements distincts (une édition n'est pas republiée comme un nouveau
 * message) ; envoyées, elles partent dans le bon salon avec la bonne relation.
 */
class TchapEditsAndReactionsTest {

	static final FakeHomeserver SERVER = FakeHomeserver.forTest(TchapEditsAndReactionsTest.class,
			hs -> hs.join("!oncall:s", "!oncall:s")
					.firstSync(FakeHomeserver.syncWithRawEvents("!oncall:s", FakeHomeserver.messageEvent("$orig", "v1"),
							FakeHomeserver.editEvent("$edit", "$orig", "v2"),
							FakeHomeserver.reactionEvent("$react", "$orig", "👍"))));

	@RegisterExtension
	static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
			.withApplicationRoot(jar -> jar.addClasses(App.class, FakeHomeserver.class)
					.addAsResource(new StringAsset("quarkus.tchap.homeserver-url=" + SERVER.url() + "\n"
							+ "quarkus.tchap.access-token=tok\n" + "quarkus.tchap.rooms.\"oncall\".room=!oncall:s\n"),
							"application.properties"));

	@Singleton
	static class App {
		@Inject
		@TchapRoomName("oncall")
		TchapRoom oncall;

		@Inject
		TchapClient client;

		final List<TchapMessage> messages = new CopyOnWriteArrayList<>();
		final List<TchapMessageEdited> edits = new CopyOnWriteArrayList<>();
		final List<TchapReaction> reactions = new CopyOnWriteArrayList<>();
		final List<TchapReaction> oncallReactions = new CopyOnWriteArrayList<>();

		void onMessage(@Observes TchapMessage message) {
			messages.add(message);
		}

		void onEdit(@Observes TchapMessageEdited edit) {
			edits.add(edit);
		}

		void onReaction(@Observes TchapReaction reaction) {
			reactions.add(reaction);
		}

		void onOncallReaction(@Observes @TchapRoomName("oncall") TchapReaction reaction) {
			oncallReactions.add(reaction);
		}
	}

	@Inject
	App app;

	@AfterAll
	static void stopServer() {
		SERVER.close();
	}

	private static String sendContaining(String text) throws Exception {
		FakeHomeserver.await(() -> SERVER.sends().stream().anyMatch(s -> s.contains(text)));
		return SERVER.sends().stream().filter(s -> s.contains(text)).findFirst().orElseThrow();
	}

	private static JsonNode contentOf(String send) throws Exception {
		return new ObjectMapper().readTree(send.substring(send.indexOf('{')));
	}

	@Test
	void anEdit_arrivesAsAnEditEvent_andNotAsAMessage() throws Exception {
		FakeHomeserver.await(() -> !app.edits.isEmpty() && !app.messages.isEmpty());
		Thread.sleep(200); // laisse le temps à un éventuel message en trop d'arriver

		assertEquals(1, app.messages.size(), "seul le message d'origine est publié comme message");
		assertEquals("$orig", app.messages.get(0).eventId());
		assertEquals("v1", app.messages.get(0).body());

		TchapMessageEdited edit = app.edits.get(0);
		assertEquals("$edit", edit.eventId());
		assertEquals("$orig", edit.replacesEventId());
		assertEquals("v2", edit.body());
	}

	@Test
	void aReaction_arrivesAsAReactionEvent_includingToTheRoomQualifiedObserver() throws Exception {
		FakeHomeserver.await(() -> !app.reactions.isEmpty() && !app.oncallReactions.isEmpty());

		TchapReaction reaction = app.reactions.get(0);
		assertEquals("$react", reaction.eventId());
		assertEquals("$orig", reaction.reactedEventId());
		assertEquals("👍", reaction.key());
		assertEquals("$react", app.oncallReactions.get(0).eventId());
	}

	@Test
	void roomHandle_edits_aMessage() throws Exception {
		app.oncall.edit("$mine", OutgoingMessage.text("Texte corrigé")).toCompletableFuture().join();

		String send = sendContaining("Texte corrigé");
		assertTrue(send.contains("/rooms/!oncall:s/send/m.room.message/"), send);
		JsonNode content = contentOf(send);
		assertEquals("m.replace", content.path("m.relates_to").path("rel_type").asText());
		assertEquals("$mine", content.path("m.relates_to").path("event_id").asText());
		assertEquals("Texte corrigé", content.path("m.new_content").path("body").asText());
	}

	@Test
	void roomHandle_reacts_toAMessage() throws Exception {
		app.oncall.react("$orig", "✅").toCompletableFuture().join();

		String send = sendContaining("✅");
		assertTrue(send.contains("/rooms/!oncall:s/send/m.reaction/"), send);
		JsonNode relatesTo = contentOf(send).path("m.relates_to");
		assertEquals("m.annotation", relatesTo.path("rel_type").asText());
		assertEquals("$orig", relatesTo.path("event_id").asText());
	}

	@Test
	void client_editsAndReacts_inTheGivenRoom() throws Exception {
		app.client.edit("!oncall:s", "$mine2", OutgoingMessage.text("Via le client")).toCompletableFuture().join();
		app.client.react("!oncall:s", "$orig", "🎉").toCompletableFuture().join();

		assertTrue(sendContaining("Via le client").contains("/rooms/!oncall:s/send/m.room.message/"));
		assertTrue(sendContaining("🎉").contains("/rooms/!oncall:s/send/m.reaction/"));
	}

	@Test
	void editWithAChangeOfThread_isRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> app.oncall.edit("$mine", OutgoingMessage.text("x").inThread("$root")));
	}
}
