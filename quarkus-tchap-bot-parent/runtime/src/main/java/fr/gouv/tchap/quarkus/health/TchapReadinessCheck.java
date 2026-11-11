package fr.gouv.tchap.quarkus.health;

import fr.gouv.tchap.matrix.client.ClientStatus;
import fr.gouv.tchap.matrix.client.MatrixClient;
import fr.gouv.tchap.quarkus.config.TchapConfig;
import fr.gouv.tchap.quarkus.rooms.TchapRoom;
import fr.gouv.tchap.quarkus.rooms.TchapRooms;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * Le bot est-il prêt à travailler ? Exposé en readiness (/q/health/ready).
 *
 * <p>UP quand le client est démarré (ou que la synchronisation est désactivée, auquel cas il
 * n'est jamais démarré). Les salons nommés sont listés à titre d'information : un salon facultatif
 * indisponible ne rend pas le bot « non prêt ». La progression de la boucle /sync est le rôle de
 * {@link TchapLivenessCheck}.
 */
@Readiness
public class TchapReadinessCheck implements HealthCheck {

    @Inject
    MatrixClient client;

    @Inject
    TchapConfig config;

    @Inject
    TchapRooms rooms;

    @Override
    public HealthCheckResponse call() {
        ClientStatus status = client.status();
        boolean syncEnabled = config.sync().enabled();
        boolean up = !syncEnabled || status == ClientStatus.RUNNING;

        HealthCheckResponseBuilder r = HealthCheckResponse.named("tchap-bot")
            .status(up)
            .withData("status", status.name())
            .withData("sync-enabled", syncEnabled);

        for (TchapRoom room : rooms.all()) {
            r.withData("room " + room.name(), room.isAvailable());
        }
        return r.build();
    }
}
