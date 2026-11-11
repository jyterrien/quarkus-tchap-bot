package fr.gouv.tchap.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import fr.gouv.tchap.quarkus.health.TchapLivenessCheck;
import fr.gouv.tchap.quarkus.health.TchapReadinessCheck;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;

/**
 * Deux sondes distinctes : la readiness dit que le bot est démarré, la liveness que /sync progresse.
 * Chacune n'est enregistrée que dans son groupe, avec son propre nom (pas de doublon dans /q/health).
 */
class TchapHealthTest {

    static final FakeHomeserver SERVER = FakeHomeserver.forTest(TchapHealthTest.class, hs -> hs
            .join("!oncall:s", "!oncall:s")
            .firstSync(FakeHomeserver.syncWith(new String[] { "!oncall:s", "$e1", "hello" })));

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar
                    .addClasses(FakeHomeserver.class)
                    .addAsResource(new StringAsset(
                            "quarkus.tchap.homeserver-url=" + SERVER.url() + "\n"
                                    + "quarkus.tchap.access-token=tok\n"
                                    + "quarkus.tchap.rooms.\"oncall\".room=!oncall:s\n"),
                            "application.properties"));

    @Inject
    @Readiness
    TchapReadinessCheck readiness;

    @Inject
    @Liveness
    TchapLivenessCheck liveness;

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @Test
    void readiness_isUp_andListsTheNamedRooms() throws Exception {
        FakeHomeserver.await(() -> !SERVER.syncPaths().isEmpty());

        HealthCheckResponse r = readiness.call();

        assertEquals("tchap-bot", r.getName());
        assertEquals(HealthCheckResponse.Status.UP, r.getStatus());
        assertEquals("RUNNING", r.getData().orElseThrow().get("status"));
        assertEquals(Boolean.TRUE, r.getData().orElseThrow().get("room oncall"));
    }

    @Test
    void liveness_isUp_whileSyncProgresses() throws Exception {
        FakeHomeserver.await(() -> SERVER.syncPaths().size() >= 2);

        HealthCheckResponse r = liveness.call();

        assertEquals("tchap-bot-sync", r.getName());
        assertEquals(HealthCheckResponse.Status.UP, r.getStatus());
        assertEquals("ok", r.getData().orElseThrow().get("sync"));
        assertTrue(r.getData().orElseThrow().containsKey("last-success"), r.getData().toString());
    }

    @Test
    void theTwoChecks_haveDistinctNames() {
        assertNotEquals(readiness.call().getName(), liveness.call().getName());
    }
}
