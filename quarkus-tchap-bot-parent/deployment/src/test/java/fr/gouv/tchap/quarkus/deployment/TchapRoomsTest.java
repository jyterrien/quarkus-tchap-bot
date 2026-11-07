package fr.gouv.tchap.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import fr.gouv.tchap.quarkus.events.TchapMessage;
import fr.gouv.tchap.quarkus.rooms.TchapRoom;
import fr.gouv.tchap.quarkus.rooms.TchapRoomName;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Deux salons nommés, l'un déclaré par alias. Le serveur envoie aussi un
 * message d'un troisième salon, qui ne doit jamais arriver à l'application.
 */
class TchapRoomsTest {

    static final FakeHomeserver SERVER = FakeHomeserver.forTest(TchapRoomsTest.class, hs -> hs
            .join("#oncall:s", "!oncall:s")
            .join("!supervisors:s", "!supervisors:s")
            .firstSync(FakeHomeserver.syncWith(
                    new String[] { "!oncall:s", "$e1", "from-oncall" },
                    new String[] { "!supervisors:s", "$e2", "from-supervisors" },
                    new String[] { "!other:s", "$e3", "from-other" })));

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar
                    .addClasses(App.class, FakeHomeserver.class)
                    .addAsResource(new StringAsset(
                            "quarkus.tchap.homeserver-url=" + SERVER.url() + "\n"
                                    + "quarkus.tchap.access-token=tok\n"
                                    + "quarkus.tchap.rooms.\"oncall\".room=#oncall:s\n"
                                    + "quarkus.tchap.rooms.\"supervisors\".room=!supervisors:s\n"),
                            "application.properties"));

    @Singleton
    static class App {
        @Inject
        @TchapRoomName("oncall")
        TchapRoom oncall;
        @Inject
        @TchapRoomName("supervisors")
        TchapRoom supervisors;

        final List<String> everything = new CopyOnWriteArrayList<>();
        final List<String> supervisorsOnly = new CopyOnWriteArrayList<>();

        void onAnyMessage(@Observes TchapMessage message) {
            everything.add(message.body());
        }

        void onSupervisorMessage(@Observes @TchapRoomName("supervisors") TchapMessage message) {
            supervisorsOnly.add(message.body());
        }
    }

    @Inject
    App app;

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @Test
    void injectedRooms_areBoundToTheirOwnResolvedRoom() throws Exception {
        FakeHomeserver.await(() -> SERVER.joins().size() >= 2);

        assertEquals(Optional.of("!oncall:s"), app.oncall.roomId());
        assertEquals(Optional.of("!supervisors:s"), app.supervisors.roomId());
        assertTrue(SERVER.joins().stream().anyMatch(j -> j.endsWith("#oncall:s")));
        assertTrue(SERVER.joins().stream().anyMatch(j -> j.endsWith("!supervisors:s")));
    }

    @Test
    void sendText_goesToTheRoomOfTheInjectedHandle_andNowhereElse() throws Exception {
        app.oncall.sendText("Base indisponible").toCompletableFuture().join();
        FakeHomeserver.await(() -> !SERVER.sends().isEmpty());

        assertTrue(
                SERVER.sends().stream().anyMatch(
                        s -> s.contains("/rooms/%21oncall%3As/send/") || s.contains("/rooms/!oncall:s/send/")),
                "sends=" + SERVER.sends());
        assertTrue(SERVER.sends().stream().noneMatch(s -> s.contains("supervisors")),
                "sends=" + SERVER.sends());
        assertTrue(SERVER.sends().stream().anyMatch(s -> s.contains("\"body\":\"Base indisponible\"")),
                "sends=" + SERVER.sends());
    }

    @Test
    void sync_isRestrictedToTheConfiguredRooms() throws Exception {
        FakeHomeserver.await(() -> !SERVER.syncPaths().isEmpty());

        String firstSync = SERVER.syncPaths().get(0);

        assertTrue(firstSync.contains("!oncall:s"), firstSync);
        assertFalse(firstSync.contains("!other:s"), firstSync);
    }

    @Test
    void qualifiedObserver_receivesOnlyItsRoom_unqualifiedObserverReceivesAllTrackedRooms() throws Exception {
        FakeHomeserver.await(() -> app.everything.size() >= 2);

        assertEquals(Set.of("from-oncall", "from-supervisors"), new HashSet<>(app.everything));
        assertEquals(List.of("from-supervisors"), app.supervisorsOnly);
        assertFalse(app.everything.contains("from-other"), "un salon non suivi ne doit pas arriver");
    }

}