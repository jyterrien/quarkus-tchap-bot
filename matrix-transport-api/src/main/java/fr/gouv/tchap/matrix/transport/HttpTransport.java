package fr.gouv.tchap.matrix.transport;

import java.util.concurrent.CompletionStage;

/**
 * Contrat de transport HTTP vers un homeserver Matrix.
 * Indépendant de toute bibliothèque HTTP.
 *
 * Contrat d'erreur : en cas d'échec, le CompletionStage se termine
 * exceptionnellement avec une MatrixException (ou RateLimitException pour un 429).
 */
public interface HttpTransport {

    CompletionStage<String> get(String path, String accessToken);

    CompletionStage<String> put(String path, String accessToken, String jsonBody);

    CompletionStage<String> post(String path, String accessToken, String jsonBody);
}
