package fr.gouv.tchap.matrix.core.sync;

import fr.gouv.tchap.matrix.sync.SyncTokenStore;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

public class InMemorySyncTokenStore implements SyncTokenStore {

	private final AtomicReference<String> token = new AtomicReference<>();

	@Override
	public CompletionStage<Optional<String>> load() {
		return CompletableFuture.completedFuture(Optional.ofNullable(token.get()));
	}

	@Override
	public CompletionStage<Void> save(String value) {
		token.set(value);
		return CompletableFuture.completedFuture(null);
	}
}
