package fr.gouv.tchap.matrix.core.auth;

import fr.gouv.tchap.matrix.auth.CredentialStore;
import fr.gouv.tchap.matrix.auth.Credentials;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Identifiants gardés en mémoire : perdus à l'arrêt, donc nouveau login avec le
 * mot de passe à chaque démarrage.
 */
public class InMemoryCredentialStore implements CredentialStore {

	private final AtomicReference<Credentials> stored = new AtomicReference<>();

	@Override
	public CompletionStage<Optional<Credentials>> load() {
		return CompletableFuture.completedFuture(Optional.ofNullable(stored.get()));
	}

	@Override
	public CompletionStage<Void> save(Credentials credentials) {
		stored.set(credentials);
		return CompletableFuture.completedFuture(null);
	}
}
