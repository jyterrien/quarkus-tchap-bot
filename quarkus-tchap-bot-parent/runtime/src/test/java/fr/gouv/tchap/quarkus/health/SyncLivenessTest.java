package fr.gouv.tchap.quarkus.health;

import fr.gouv.tchap.matrix.client.ClientStatus;
import fr.gouv.tchap.matrix.sync.SyncHealth;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Test unitaire pur : aucun démarrage de Quarkus. */
class SyncLivenessTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
    private static final Duration MAX = Duration.ofMinutes(2);

    private static SyncLiveness.Verdict eval(boolean enabled, ClientStatus status, SyncHealth health) {
        return SyncLiveness.evaluate(enabled, status, health, MAX, NOW);
    }

    @Test
    void syncDisabled_isNeverAFailure() {
        SyncLiveness.Verdict v = eval(false, ClientStatus.NEW, SyncHealth.notStarted());

        assertTrue(v.up());
        assertEquals("disabled", v.state());
    }

    @Test
    void recentSuccess_isUp() {
        SyncHealth h = new SyncHealth(NOW.minusSeconds(600), NOW.minusSeconds(20), 0, null);

        SyncLiveness.Verdict v = eval(true, ClientStatus.RUNNING, h);

        assertTrue(v.up());
        assertEquals("ok", v.state());
    }

    @Test
    void aFewFailuresButRecentSuccess_isStillUp() {
        SyncHealth h = new SyncHealth(NOW.minusSeconds(600), NOW.minusSeconds(60), 3, "MatrixException: HTTP 500");

        assertTrue(eval(true, ClientStatus.RUNNING, h).up());
    }

    @Test
    void noSuccessForLongerThanTheLimit_isDownEvenWithoutAnyError() {
        // Boucle bloquée : aucune erreur comptée, mais plus aucune réponse non plus
        SyncHealth h = new SyncHealth(NOW.minusSeconds(3_600), NOW.minusSeconds(300), 0, null);

        SyncLiveness.Verdict v = eval(true, ClientStatus.RUNNING, h);

        assertFalse(v.up());
        assertEquals("stalled", v.state());
    }

    @Test
    void exactlyAtTheLimit_isStillUp() {
        SyncHealth h = new SyncHealth(NOW.minusSeconds(600), NOW.minus(MAX), 0, null);

        assertTrue(eval(true, ClientStatus.RUNNING, h).up());
    }

    @Test
    void neverSucceeded_isMeasuredFromTheStart() {
        SyncHealth recentStart = new SyncHealth(NOW.minusSeconds(30), null, 0, null);
        SyncHealth oldStart = new SyncHealth(NOW.minusSeconds(300), null, 5, "MatrixException: HTTP 502");

        assertTrue(eval(true, ClientStatus.RUNNING, recentStart).up());
        assertFalse(eval(true, ClientStatus.RUNNING, oldStart).up());
    }

    @Test
    void failedClient_isDown() {
        assertFalse(eval(true, ClientStatus.FAILED, SyncHealth.notStarted()).up());
    }

    @Test
    void notRunningYet_orStopping_isNotAFailure() {
        for (ClientStatus status : new ClientStatus[] {
                ClientStatus.NEW, ClientStatus.STARTING, ClientStatus.STOPPING, ClientStatus.STOPPED }) {
            SyncLiveness.Verdict v = eval(true, status, SyncHealth.notStarted());

            assertTrue(v.up(), status.name());
            assertEquals("not-running", v.state());
        }
    }
}
