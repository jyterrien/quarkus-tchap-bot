package fr.gouv.tchap.matrix.transport;

import java.util.concurrent.CompletionStage;

/**
 * Contrat de transport HTTP vers un homeserver Matrix. Indépendant de toute
 * bibliothèque HTTP. Contrat d'erreur : en cas d'échec, le CompletionStage se
 * termine exceptionnellement avec une MatrixException (ou RateLimitException
 * pour un 429). Cycle de vie : celui qui crée un transport en est propriétaire
 * et doit le fermer. Ni MatrixClient ni les services du core ne ferment le
 * transport qu'on leur injecte. {@link #close()} ne fait rien par défaut
 * (transports sans ressource à libérer).
 */
public interface HttpTransport extends AutoCloseable {

	CompletionStage<String> get(String path, String accessToken);

	CompletionStage<String> put(String path, String accessToken, String jsonBody);

	CompletionStage<String> post(String path, String accessToken, String jsonBody);

	/** Libère les ressources du transport. Idempotent. Sans exception vérifiée. */
	@Override
	default void close() {
	}
}
