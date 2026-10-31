package fr.gouv.tchap.matrix.transport.http;

import fr.gouv.tchap.matrix.transport.HttpTransport;
import io.vertx.core.Vertx;

/**
 * Fabrique utilitaire pour l'assemblage côté application.
 * L'extension Quarkus peut faire ce câblage directement via CDI
 * en injectant le Vertx géré par le runtime ; cette fabrique reste
 * utile hors Quarkus.
 *
 * Dans tous les cas, l'appelant est propriétaire du transport retourné
 * et doit appeler {@link HttpTransport#close()} quand il n'en a plus besoin.
 */
public final class TransportFactory {

    private TransportFactory() {}

    /**
     * Crée un transport avec sa propre instance Vertx.
     * {@code close()} arrête aussi ce Vertx.
     */
    public static HttpTransport vertx(String homeserverUrl) {
        return new VertxHttpTransport(homeserverUrl);
    }

    /**
     * Crée un transport sur une instance Vertx existante (ex. celle de Quarkus).
     * {@code close()} ne ferme PAS ce Vertx.
     */
    public static HttpTransport vertx(Vertx vertx, String homeserverUrl) {
        return new VertxHttpTransport(vertx, homeserverUrl);
    }
}
