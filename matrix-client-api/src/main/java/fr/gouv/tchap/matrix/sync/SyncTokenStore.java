package fr.gouv.tchap.matrix.sync;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Persistance du token de synchronisation (next_batch / since). MVP :
 * implémentations InMemory et File dans matrix-client-core.
 */
public interface SyncTokenStore {
	CompletionStage<Optional<String>> load();

	CompletionStage<Void> save(String token);
}
