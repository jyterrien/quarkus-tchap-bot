package fr.gouv.tchap.matrix.transport.http;

import fr.gouv.tchap.matrix.transport.HttpTransport;
import io.vertx.core.Vertx;

/**
 * Fabrique utilitaire pour l'assemblage côté application.
 * L'extension Quarkus peut faire ce câblage directement via CDI
 * en injectant le Vertx géré par le runtime ; cette fabrique reste
 * utile hors Quarkus.
 */
public final class TransportFactory {

    private TransportFactory() {}

    /** Crée un transport avec sa propre instance Vertx. */
    public static HttpTransport vertx(String homeserverUrl) {
        return new VertxHttpTransport(homeserverUrl);
    }

    /** Crée un transport sur une instance Vertx existante (ex. celle de Quarkus). */
    public static HttpTransport vertx(Vertx vertx, String homeserverUrl) {
        return new VertxHttpTransport(vertx, homeserverUrl);
    }
}
