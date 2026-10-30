package fr.gouv.tchap.matrix.client;

import fr.gouv.tchap.matrix.auth.Session;
import fr.gouv.tchap.matrix.message.MessageService;
import fr.gouv.tchap.matrix.room.RoomService;
import java.util.concurrent.CompletionStage;

/**
 * Façade centrale du client Matrix. Indépendant de Quarkus.
 * Référence spec : Client-Server API v1.19, section "Client Authentication" et "Sync".
 */
public interface MatrixClient {

    /** Démarre le client (authentification + sync initial). */
    CompletionStage<Void> start();

    /** Arrête proprement le client. */
    CompletionStage<Void> stop();

    MessageService messages();
    RoomService rooms();
    Session session();
    ClientStatus status();
}