package fr.gouv.tchap.quarkus.deployment;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;

/**
 * Ajustements spécifiques à l'image native. Pour le MVP : déclare les
 * ressources nécessaires à Jackson.
 */
public class TchapNativeProcessor {

	@BuildStep
	void registerResources(BuildProducer<NativeImageResourceBuildItem> resources) {
		// Aucune ressource Matrix spécifique pour l'instant.
		// Le placeholder est conservé pour accueillir plus tard
		// les données crypto (pré-keys, etc.) si nécessaire.
	}
}
