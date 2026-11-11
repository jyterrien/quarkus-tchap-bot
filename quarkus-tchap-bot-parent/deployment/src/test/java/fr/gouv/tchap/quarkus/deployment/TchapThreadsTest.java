package fr.gouv.tchap.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.gouv.tchap.quarkus.TchapClient;
import fr.gouv.tchap.quarkus.events.TchapMessage;
import fr.gouv.tchap.quarkus.rooms.TchapRoom;
import fr.gouv.tchap.quarkus.rooms.TchapRoomName;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Prise en charge des threads de bout en bout : la racine du thread d'un
 * message reçu arrive jusqu'aux observers, et les envois dans un thread portent
 * la relation m.thread vers le bon salon.
 */
class TchapThreadsTest {

	static final FakeHomeserver SERVER = FakeHomeserver.forTest(TchapThreadsTest.class,
			hs -> hs.join("!oncall:s", "!oncall:s").join("!supervisors:s", "!supervisors:s")
					.firstSync(FakeHomeserver.syncWith(new String[]{"!oncall:s", "$reply", "dans-le-thread", "$root"},
							new String[]{"!supervisors:s", "$plain", "hors-thread"})));

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

	@Test
	void threadReply_exposesItsThreadRoot_toObservers() throws Exception {
		FakeHomeserver.await(() -> app.byEventId.size() >= 2);

		TchapMessage reply = app.byEventId.get("$reply");
		assertEquals("$root", reply.threadRootId());
		assertTrue(reply.isInThread());
		assertEquals("$root", reply.replyThreadId());
	}

	@Test
	void plainMessage_hasNoThreadRoot() throws Exception {
		FakeHomeserver.await(() -> app.byEventId.size() >= 2);

		TchapMessage plain = app.byEventId.get("$plain");
		assertNull(plain.threadRootId());
		assertFalse(plain.isInThread());
		assertEquals("$plain", plain.replyThreadId());
	}

	@Test
	void sendTextInThread_viaTheRoomHandle_goesToItsRoomWithTheThreadRelation() throws Exception {
		app.oncall.sendTextInThread("$root", "Suite du fil").toCompletableFuture().join();
		FakeHomeserver.await(() -> hasSend("Suite du fil"));

		String send = sendContaining("Suite du fil");
		assertTrue(send.contains("/rooms/!oncall:s/send/"), send);
		assertTrue(send.contains("\"rel_type\":\"m.thread\""), send);
		assertTrue(send.contains("\"event_id\":\"$root\""), send);
		assertTrue(send.contains("\"msgtype\":\"m.text\""), send);
	}

	@Test
	void sendNoticeInThread_viaTheRoomHandle_usesTheNoticeMsgtype() throws Exception {
		app.oncall.sendNoticeInThread("$root", "Alerte dans le fil").toCompletableFuture().join();
		FakeHomeserver.await(() -> hasSend("Alerte dans le fil"));

		String send = sendContaining("Alerte dans le fil");
		assertTrue(send.contains("\"msgtype\":\"m.notice\""), send);
		assertTrue(send.contains("\"rel_type\":\"m.thread\""), send);
	}

	@Test
	void sendTextInThread_viaTchapClient_goesToTheGivenRoom() throws Exception {
		app.client.sendTextInThread("!supervisors:s", "$root", "Réponse client").toCompletableFuture().join();
		FakeHomeserver.await(() -> hasSend("Réponse client"));

		String send = sendContaining("Réponse client");
		assertTrue(send.contains("/rooms/!supervisors:s/send/"), send);
		assertTrue(send.contains("\"rel_type\":\"m.thread\""), send);
	}

	@Test
	void sendInThread_withoutThreadRoot_isRejected() {
		assertThrows(IllegalArgumentException.class, () -> app.oncall.sendTextInThread(" ", "x"));
		assertThrows(IllegalArgumentException.class, () -> app.client.sendTextInThread("!oncall:s", null, "x"));
	}

	private static boolean hasSend(String body) {
		return !sendsContaining(body).isEmpty();
	}

	private static String sendContaining(String body) {
		return sendsContaining(body).get(0);
	}

	private static List<String> sendsContaining(String body) {
		return SERVER.sends().stream().filter(s -> s.contains("\"body\":\"" + body + "\"")).toList();
	}
}
