package fr.gouv.tchap.quarkus.health;

import fr.gouv.tchap.matrix.client.MatrixClient;
import fr.gouv.tchap.matrix.sync.SyncHealth;
import fr.gouv.tchap.quarkus.config.TchapConfig;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Liveness;

import java.time.Duration;
import java.time.Instant;

/**
 * La boucle de synchronisation progresse-t-elle ? Exposé en liveness (/q/health/live).
 *
 * <p>DOWN quand aucune réponse /sync n'a abouti depuis {@code quarkus.tchap.health.sync-max-silence} :
 * boucle bloquée, ou homeserver injoignable depuis trop longtemps. Un échec isolé (réessayé au bout
 * de quelques secondes) ne fait pas passer la sonde à DOWN.
 *
 * <p>Nom distinct de {@link TchapReadinessCheck} : les deux sondes sont agrégées dans /q/health.
 */
@Liveness
public class TchapLivenessCheck implements HealthCheck {

    @Inject
    MatrixClient client;

    @Inject
    TchapConfig config;

    @Override
    public HealthCheckResponse call() {
        Instant now = Instant.now();
        SyncHealth health = client.syncHealth();
        SyncLiveness.Verdict verdict = SyncLiveness.evaluate(
            config.sync().enabled(), client.status(), health, config.health().syncMaxSilence(), now);

        HealthCheckResponseBuilder r = HealthCheckResponse.named("tchap-bot-sync")
            .status(verdict.up())
            .withData("sync", verdict.state());

        if (health != null && health.isStarted()) {
            if (health.lastSuccess() != null) {
                r.withData("last-success", health.lastSuccess().toString());
                r.withData("seconds-since-last-success", Duration.between(health.lastSuccess(), now).toSeconds());
            }
            r.withData("consecutive-failures", health.consecutiveFailures());
            if (health.lastError() != null) {
                r.withData("last-error", health.lastError());
            }
        }
        return r.build();
    }
}
