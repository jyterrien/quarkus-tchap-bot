package fr.gouv.tchap.matrix.auth;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Persistance des identifiants de session (token d'accès, token de
 * renouvellement, device).
 *
 * <p>
 * Le token de renouvellement est à usage unique : chaque renouvellement en émet
 * un nouveau, et l'ancien cesse de valoir. Un magasin qui ne persiste pas
 * (mémoire) oblige donc à se reconnecter avec le mot de passe à chaque
 * démarrage. {@code save} est appelé depuis un thread Vert.x : ne jamais y
 * bloquer.
 */
public interface CredentialStore {
	CompletionStage<Optional<Credentials>> load();
	CompletionStage<Void> save(Credentials credentials);
}
