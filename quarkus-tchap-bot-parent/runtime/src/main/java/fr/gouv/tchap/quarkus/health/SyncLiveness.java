package fr.gouv.tchap.quarkus.health;

import fr.gouv.tchap.matrix.client.ClientStatus;
import fr.gouv.tchap.matrix.sync.SyncHealth;

import java.time.Duration;
import java.time.Instant;

/**
 * Règle de décision de la sonde de liveness, sans dépendance à CDI ni à MicroProfile :
 * testable sans démarrer Quarkus.
 */
final class SyncLiveness {

    /** @param up vrai si la boucle est considérée en bonne santé ; @param state libellé pour le diagnostic */
    record Verdict(boolean up, String state) {}

    private SyncLiveness() {
    }

    /**
     * La boucle /sync est vivante si elle a abouti il y a moins de {@code maxSilence} (ou, tant
     * qu'aucune réponse n'a abouti, si elle a démarré il y a moins de {@code maxSilence}).
     * Ne sont pas des pannes : synchronisation désactivée, client pas encore démarré ou en arrêt.
     */
    static Verdict evaluate(boolean syncEnabled, ClientStatus status, SyncHealth health,
                            Duration maxSilence, Instant now) {
        if (!syncEnabled) {
            return new Verdict(true, "disabled");
        }
        if (status == ClientStatus.FAILED) {
            return new Verdict(false, "failed");
        }
        if (status != ClientStatus.RUNNING || health == null || !health.isStarted()) {
            return new Verdict(true, "not-running");
        }
        Instant reference = health.lastSuccess() != null ? health.lastSuccess() : health.startedAt();
        boolean fresh = Duration.between(reference, now).compareTo(maxSilence) <= 0;
        return new Verdict(fresh, fresh ? "ok" : "stalled");
    }
}
