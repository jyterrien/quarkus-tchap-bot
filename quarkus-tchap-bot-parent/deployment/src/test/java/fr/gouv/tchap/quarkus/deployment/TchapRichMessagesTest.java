package fr.gouv.tchap.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.gouv.tchap.matrix.message.OutgoingMessage;
import fr.gouv.tchap.matrix.message.OutgoingMessage.Type;
import fr.gouv.tchap.quarkus.TchapClient;
import fr.gouv.tchap.quarkus.events.TchapMessage;
import fr.gouv.tchap.quarkus.rooms.TchapRoom;
import fr.gouv.tchap.quarkus.rooms.TchapRoomName;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Messages enrichis de bout en bout : la cible d'une réponse reçue arrive aux
 * observers, et les réponses, emotes et messages HTML partent dans le bon salon
 * avec le bon contenu.
 */
class TchapRichMessagesTest {

	static final FakeHomeserver SERVER = FakeHomeserver.forTest(TchapRichMessagesTest.class,
			hs -> hs.join("!oncall:s", "!oncall:s").join("!supervisors:s", "!supervisors:s")
					.firstSync(FakeHomeserver.syncWith(new String[]{"!oncall:s", "$reply", "re", null, "$question"},
							new String[]{"!supervisors:s", "$plain", "salut"})));

	@RegisterExtension
	static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
			.withApplicationRoot(jar -> jar.addClasses(App.class, FakeHomeserver.class)
					.addAsResource(new StringAsset("quarkus.tchap.homeserver-url=" + SERVER.url() + "\n"
							+ "quarkus.tchap.access-token=tok\n" + "quarkus.tchap.rooms.\"oncall\".room=!oncall:s\n"
							+ "quarkus.tchap.rooms.\"supervisors\".room=!supervisors:s\n"), "application.properties"));

	@Singleton
	static class App {
		@Inject
		@TchapRoomName("oncall")
		TchapRoom oncall;

		@Inject
		TchapClient client;

		final Map<String, TchapMessage> byEventId = new ConcurrentHashMap<>();

		void onMessage(@Observes TchapMessage message) {
			byEventId.put(message.eventId(), message);
		}
	}

	@Inject
	App app;

	@AfterAll
	static void stopServer() {
		SERVER.close();
	}

	private static String sendContaining(String body) throws Exception {
		FakeHomeserver.await(() -> SERVER.sends().stream().anyMatch(s -> s.contains("\"body\":\"" + body + "\"")));
		return SERVER.sends().stream().filter(s -> s.contains("\"body\":\"" + body + "\"")).findFirst().orElseThrow();
	}

	private static JsonNode contentOf(String send) throws Exception {
		return new ObjectMapper().readTree(send.substring(send.indexOf('{')));
	}

	@Test
	void receivedReply_exposesItsTarget_toObservers() throws Exception {
		FakeHomeserver.await(() -> app.byEventId.size() >= 2);

		TchapMessage reply = app.byEventId.get("$reply");
		assertEquals("$question", reply.replyToEventId());
		assertTrue(reply.isReply());

		TchapMessage plain = app.byEventId.get("$plain");
		assertNull(plain.replyToEventId());
		assertFalse(plain.isReply());
	}

	@Test
	void replyToAReceivedMessage_goesToItsRoom_asAnInReplyToRelation() throws Exception {
		FakeHomeserver.await(() -> app.byEventId.containsKey("$plain"));
		TchapMessage plain = app.byEventId.get("$plain");

		app.client.send(plain.roomId(), plain.reply("Reçu sans fil")).toCompletableFuture().join();

		String send = sendContaining("Reçu sans fil");
		assertTrue(send.contains("/rooms/!supervisors:s/send/"), send);
		JsonNode relatesTo = contentOf(send).path("m.relates_to");
		assertEquals("$plain", relatesTo.path("m.in_reply_to").path("event_id").asText());
		assertTrue(relatesTo.path("rel_type").isMissingNode(), "pas de thread ouvert");
	}

	@Test
	void roomHandle_sendsEmote_andHtml() throws Exception {
		app.oncall.send(OutgoingMessage.emote("surveille la file")).toCompletableFuture().join();
		app.oncall
				.send(OutgoingMessage.notice("Déploiement OK")
						.withHtml("<b>" + OutgoingMessage.escapeHtml("Déploiement") + "</b> OK"))
				.toCompletableFuture().join();

		JsonNode emote = contentOf(sendContaining("surveille la file"));
		assertEquals("m.emote", emote.path("msgtype").asText());

		String htmlSend = sendContaining("Déploiement OK");
		assertTrue(htmlSend.contains("/rooms/!oncall:s/send/"), htmlSend);
		JsonNode html = contentOf(htmlSend);
		assertEquals("m.notice", html.path("msgtype").asText());
		assertEquals("org.matrix.custom.html", html.path("format").asText());
		assertEquals("<b>Déploiement</b> OK", html.path("formatted_body").asText());
	}

	@Test
	void replyInsideAThread_keepsTheThread_withTheRealTarget() throws Exception {
		app.oncall.send(OutgoingMessage.text("Dans le fil").inReplyTo("$q2").inThread("$root2").withType(Type.TEXT))
				.toCompletableFuture().join();

		JsonNode relatesTo = contentOf(sendContaining("Dans le fil")).path("m.relates_to");
		assertEquals("m.thread", relatesTo.path("rel_type").asText());
		assertEquals("$root2", relatesTo.path("event_id").asText());
		assertFalse(relatesTo.path("is_falling_back").asBoolean(true));
		assertEquals("$q2", relatesTo.path("m.in_reply_to").path("event_id").asText());
	}
}
