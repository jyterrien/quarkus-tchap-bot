package fr.gouv.tchap.quarkus.health;

import fr.gouv.tchap.matrix.client.ClientStatus;
import fr.gouv.tchap.matrix.client.MatrixClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;

@Liveness
@Readiness
@ApplicationScoped
public class TchapHealthCheck implements HealthCheck {

    @Inject
    MatrixClient client;

    @Override
    public HealthCheckResponse call() {
        ClientStatus status = client.status();
        boolean up = status == ClientStatus.RUNNING;
        return HealthCheckResponse.named("tchap-bot")
            .status(up)
            .withData("status", status.name())
            .build();
    }
}
