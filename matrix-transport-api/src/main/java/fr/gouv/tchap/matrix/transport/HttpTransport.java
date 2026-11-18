package fr.gouv.tchap.matrix.transport;

import java.util.concurrent.CompletionStage;

/**
 * Contrat de transport HTTP vers un homeserver Matrix. Indépendant de toute
 * bibliothèque HTTP. Contrat d'erreur : en cas d'échec, le CompletionStage se
 * termine exceptionnellement avec une MatrixException : une
 * MatrixTransportException quand la requête n'a pas abouti (réseau, connexion
 * coupée, transport fermé), une MatrixServerException pour un statut HTTP
 * 4xx/5xx (elle porte le statut et le code d'erreur Matrix), et sa sous-classe
 * RateLimitException pour un 429. {@code accessToken} peut être {@code null} :
 * aucun en-tête Authorization n'est alors envoyé (requêtes {@code /login} et
 * {@code /refresh}). Cycle de vie : celui qui crée un transport en est
 * propriétaire et doit le fermer. Ni MatrixClient ni les services du core ne
 * ferment le transport qu'on leur injecte. {@link #close()} ne fait rien par
 * défaut (transports sans ressource à libérer).
 */
public interface HttpTransport extends AutoCloseable {

	CompletionStage<String> get(String path, String accessToken);

	CompletionStage<String> put(String path, String accessToken, String jsonBody);

	CompletionStage<String> post(String path, String accessToken, String jsonBody);

	/** Libère les ressources du transport. Idempotent. Sans exception vérifiée. */
	/**
	 * {@code DELETE}. Équivalent de {@link #send} avec une requête {@code DELETE}
	 * sans corps.
	 */
	default CompletionStage<String> delete(String path, String accessToken) {
		return send(TransportRequest.of("DELETE", path, accessToken));
	}

	/**
	 * Requête quelconque : méthode, en-têtes supplémentaires, corps binaire et
	 * délai propre. Même contrat d'erreur que les autres méthodes. Par défaut non
	 * pris en charge (échec {@link UnsupportedOperationException}) : un transport
	 * qui ne la fournit pas reste utilisable pour tout le reste.
	 */
	default CompletionStage<String> send(TransportRequest request) {
		return java.util.concurrent.CompletableFuture.failedFuture(
				new UnsupportedOperationException("Generic requests are not supported by " + getClass().getName()));
	}

	@Override
	default void close() {
	}
}
