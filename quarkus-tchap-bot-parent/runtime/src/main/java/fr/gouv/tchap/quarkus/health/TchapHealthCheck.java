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
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;

@Liveness
@Readiness
public class TchapHealthCheck implements HealthCheck {

    @Inject
    MatrixClient client;

    @Inject
    TchapConfig config;

    @Inject
    TchapRooms rooms;

    //TODO: en double dans la réponse !!!
    @Override
    public HealthCheckResponse call() {
        ClientStatus status = client.status();
        boolean syncEnabled = config.sync().enabled();
        // Sync désactivée : le client n'est jamais démarré, ce n'est pas une panne
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
