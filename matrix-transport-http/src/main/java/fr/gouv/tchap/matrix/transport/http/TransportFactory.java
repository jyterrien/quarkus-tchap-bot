package fr.gouv.tchap.matrix.transport.http;

import fr.gouv.tchap.matrix.core.transport.MatrixTransport;
import fr.gouv.tchap.matrix.transport.HttpTransport;

/**
 * Fabrique utilitaire pour l'assemblage côté application.
 * L'extension Quarkus (module runtime) peut également faire ce câblage
 * directement via CDI ; cette fabrique reste utile hors Quarkus.
 */
public final class TransportFactory {

    private TransportFactory() {}

    public static HttpTransport jdk(String homeserverUrl) {
        return new JdkHttpTransport(homeserverUrl);
    }

    public static MatrixTransport matrixJdk(String homeserverUrl) {
        return new MatrixHttpTransportAdapter(jdk(homeserverUrl));
    }

    public static MatrixTransport matrix(HttpTransport transport) {
        return new MatrixHttpTransportAdapter(transport);
    }
}
