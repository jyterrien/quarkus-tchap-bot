package fr.gouv.tchap.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.quarkus.rooms.TchapRoom;
import fr.gouv.tchap.quarkus.rooms.TchapRoomName;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Un salon facultatif inaccessible n'empêche pas le démarrage ; ses envois
 * échouent proprement.
 */
class TchapRoomsOptionalTest {

	static final FakeHomeserver SERVER = FakeHomeserver.forTest(TchapRoomsOptionalTest.class,
			hs -> hs.firstSync("{\"next_batch\":\"s1\"}").join("!ok:s", "!ok:s").joinForbidden("!gone:s"));

	@RegisterExtension
	static final QuarkusExtensionTest TEST = new QuarkusExtensionTest().withApplicationRoot(jar -> jar
			.addClasses(App.class, FakeHomeserver.class)
			.addAsResource(new StringAsset("quarkus.tchap.homeserver-url=" + SERVER.url() + "\n"
					+ "quarkus.tchap.access-token=tok\n" + "quarkus.tchap.rooms.\"ok\".room=!ok:s\n"
					+ "quarkus.tchap.rooms.\"gone\".room=!gone:s\n" + "quarkus.tchap.rooms.\"gone\".required=false\n"),
					"application.properties"));

	@Singleton
	static class App {
		@Inject
		@TchapRoomName("ok")
		TchapRoom ok;
		@Inject
		@TchapRoomName("gone")
		TchapRoom gone;
	}

	@Inject
	App app;

	@AfterAll
	static void stopServer() {
		SERVER.close();
	}

	@Test
	void optionalRoomIsUnavailable_requiredRoomIsAvailable() {
		assertTrue(app.ok.isAvailable());
		assertFalse(app.gone.isAvailable());
		assertEquals(Optional.empty(), app.gone.roomId());
		assertTrue(app.gone.unavailableReason().orElseThrow().contains("M_FORBIDDEN"));
	}

	@Test
	void sendingToAnUnavailableRoom_failsWithoutTouchingTheServer() {
		CompletionException ex = assertThrows(CompletionException.class,
				() -> app.gone.sendText("hello").toCompletableFuture().join());

		assertInstanceOf(IllegalStateException.class, ex.getCause());
		assertTrue(ex.getCause().getMessage().contains("not available"));
		assertTrue(SERVER.sends().isEmpty());
	}

	@Test
	void sync_isRestrictedToTheAvailableRoomsOnly() throws Exception {
		FakeHomeserver.await(() -> !SERVER.syncPaths().isEmpty());

		String firstSync = SERVER.syncPaths().get(0);
		assertTrue(firstSync.contains("!ok:s"), firstSync);
		assertFalse(firstSync.contains("!gone:s"), firstSync);
	}
}
