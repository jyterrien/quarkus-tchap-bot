package fr.gouv.tchap.quarkus.deployment;

import fr.gouv.tchap.quarkus.TchapRecorder;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;

import static io.quarkus.deployment.annotations.ExecutionTime.STATIC_INIT;

public class TchapProcessor {

    private static final String FEATURE = "tchap-bot";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    TchapFeatureBuildItem tchapFeature(TchapBuildTimeConfig buildTimeConfig) {
        // La référence à TchapConfig force Quarkus à l'enregistrer comme bean CDI.
        return new TchapFeatureBuildItem(false);
    }

    @BuildStep
    void registerForReflection(BuildProducer<ReflectiveClassBuildItem> reflective) {
        reflective.produce(ReflectiveClassBuildItem
                .builder(
                        "fr.gouv.tchap.matrix.core.sync.SyncEventParser",
                        "fr.gouv.tchap.matrix.transport.http.VertxHttpTransport",
                        "fr.gouv.tchap.quarkus.TchapClient",
                        "fr.gouv.tchap.quarkus.events.TchapMessage",
                        "fr.gouv.tchap.quarkus.events.TchapEventPublisher")
                .methods(true)
                .fields(true)
                .build());
    }

    @BuildStep
    void registerJacksonModules(BuildProducer<ServiceProviderBuildItem> services) {
        services.produce(ServiceProviderBuildItem.allProvidersFromClassPath(
                "com.fasterxml.jackson.databind.Module"));
    }

    @BuildStep
    @Record(STATIC_INIT)
    void logStartup(TchapRecorder recorder, TchapBuildTimeConfig config) {
        recorder.logStartup(false);
    }
}
