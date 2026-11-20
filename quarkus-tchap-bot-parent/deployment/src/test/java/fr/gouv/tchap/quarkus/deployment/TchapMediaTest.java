package fr.gouv.tchap.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.gouv.tchap.matrix.media.MediaContent;
import fr.gouv.tchap.quarkus.TchapClient;
import fr.gouv.tchap.quarkus.events.TchapMessage;
import fr.gouv.tchap.quarkus.rooms.TchapRoom;
import fr.gouv.tchap.quarkus.rooms.TchapRoomName;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Médias de bout en bout : un média reçu se télécharge, un fichier se téléverse
 * puis s'envoie dans le salon.
 */
class TchapMediaTest {

	static final byte[] RECEIVED = {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, (byte) 255, (byte) 128};
	static final byte[] TO_SEND = {1, 2, 3, 4, 5, (byte) 250};

	static final FakeHomeserver SERVER = FakeHomeserver.forTest(TchapMediaTest.class, hs -> hs
			.join("!oncall:s", "!oncall:s").upload("mxc://srv/uploaded1")
			.media("srv", "received1", RECEIVED, "image/png", "attachment; filename=\"photo.png\"")
			.firstSync(FakeHomeserver.syncWithRawEvents("!oncall:s",
					FakeHomeserver.mediaEvent("$img", "m.image", "photo.png", "mxc://srv/received1", "image/png", 9))));

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

		void onMessage(@Observes TchapMessage message) {
			messages.add(message);
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

	@Test
	void aReceivedImage_exposesItsMedia_andCanBeDownloaded() throws Exception {
		FakeHomeserver.await(() -> !app.messages.isEmpty());

		TchapMessage message = app.messages.get(0);
		assertTrue(message.hasMedia());
		assertEquals("m.image", message.messageType());
		assertEquals("mxc://srv/received1", message.media().url());
		assertEquals("image/png", message.media().mimeType());
		assertEquals(9L, message.media().size());

		MediaContent content = app.client.download(message.media()).toCompletableFuture().join();

		assertArrayEquals(RECEIVED, content.content(), "les octets doivent arriver sans altération");
		assertEquals("image/png", content.contentType());
		assertEquals("photo.png", content.filename());
	}

	@Test
	void sendMedia_viaTheRoom_uploadsThenPostsAnImageMessage() throws Exception {
		app.oncall.sendMedia(TO_SEND, "image/png", "chart.png", "Mon graphique").toCompletableFuture().join();

		var uploads = SERVER.uploads();
		assertFalse(uploads.isEmpty());
		var upload = uploads.get(uploads.size() - 1);
		assertEquals("Bearer tok", upload.getHeader("Authorization"));
		assertEquals("image/png", upload.getHeader("Content-Type"));
		assertEquals("filename=chart.png", upload.getUrl().substring(upload.getUrl().indexOf('?') + 1));
		assertArrayEquals(TO_SEND, upload.getBody(), "le fichier doit être envoyé sans altération");

		String send = sendContaining("Mon graphique");
		assertTrue(send.contains("/rooms/!oncall:s/send/m.room.message/"), send);
		JsonNode sent = new ObjectMapper().readTree(send.substring(send.indexOf('{')));
		assertEquals("m.image", sent.path("msgtype").asText());
		assertEquals("Mon graphique", sent.path("body").asText());
		assertEquals("mxc://srv/uploaded1", sent.path("url").asText());
		assertEquals("chart.png", sent.path("filename").asText());
		assertEquals(TO_SEND.length, sent.path("info").path("size").asInt());
	}

	@Test
	void sendMedia_viaTheClient_picksAFileMessageForADocument() throws Exception {
		app.client.sendMedia("!oncall:s", "contenu du rapport".getBytes(StandardCharsets.UTF_8), "application/pdf",
				"rapport.pdf", null).toCompletableFuture().join();

		String send = sendContaining("rapport.pdf");
		JsonNode sent = new ObjectMapper().readTree(send.substring(send.indexOf('{')));
		assertEquals("m.file", sent.path("msgtype").asText());
		assertEquals("rapport.pdf", sent.path("body").asText(), "sans légende, le nom du fichier");
		assertEquals("application/pdf", sent.path("info").path("mimetype").asText());
	}

	@Test
	void upload_returnsTheMxcUriOfTheNewMedia() {
		var uri = app.client.upload(TO_SEND, "application/octet-stream", "data.bin").toCompletableFuture().join();

		assertEquals("mxc://srv/uploaded1", uri.toString());
	}
}
