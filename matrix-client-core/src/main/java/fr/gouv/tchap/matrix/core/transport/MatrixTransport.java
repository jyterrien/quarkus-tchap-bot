package fr.gouv.tchap.matrix.core.transport;

import java.util.concurrent.CompletionStage;

public interface MatrixTransport {

    CompletionStage<String> get(String path, String accessToken);

    CompletionStage<String> put(String path, String accessToken, String jsonBody);
}
