package fr.gouv.tchap.matrix.transport.http;

import fr.gouv.tchap.matrix.core.transport.MatrixTransport;
import fr.gouv.tchap.matrix.transport.HttpTransport;

import java.util.concurrent.CompletionStage;

/**
 * Pont entre le port interne du core (MatrixTransport)
 * et le contrat public de transport (HttpTransport).
 *
 * Permet au core d'ignorer toute implémentation concrète
 * (JDK, Vert.x, mock de test, etc.).
 */
public class MatrixHttpTransportAdapter implements MatrixTransport {

    private final HttpTransport delegate;

    public MatrixHttpTransportAdapter(HttpTransport delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate is required");
        }
        this.delegate = delegate;
    }

    @Override
    public CompletionStage<String> get(String path, String accessToken) {
        return delegate.get(path, accessToken);
    }

    @Override
    public CompletionStage<String> put(String path, String accessToken, String jsonBody) {
        return delegate.put(path, accessToken, jsonBody);
    }
}
