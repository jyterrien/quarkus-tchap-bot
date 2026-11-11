package fr.gouv.tchap.quarkus.deployment;

import io.quarkus.builder.item.SimpleBuildItem;

/**
 * Build item marqueur : indique que l'extension Tchap Bot est active dans le
 * build. D'autres extensions peuvent en dépendre pour augmenter l'intégration.
 */
public final class TchapFeatureBuildItem extends SimpleBuildItem {

	private final boolean cryptoEnabled;

	public TchapFeatureBuildItem(boolean cryptoEnabled) {
		this.cryptoEnabled = cryptoEnabled;
	}

	public boolean cryptoEnabled() {
		return cryptoEnabled;
	}
}
