package fr.gouv.tchap.matrix.transport;

import java.util.concurrent.CompletionStage;

/**
 * Abstraction de transport HTTP indépendante de Vert.x.
 */
public interface HttpTransport {

    CompletionStage<String> get(String path, String accessToken);

    CompletionStage<String> put(String path, String accessToken, String jsonBody);
}
