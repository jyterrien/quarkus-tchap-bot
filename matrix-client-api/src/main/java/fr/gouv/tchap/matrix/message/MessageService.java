package fr.gouv.tchap.matrix.message;

import java.util.concurrent.CompletionStage;

public interface MessageService {
    /**
     * Envoie un message texte simple dans une salle.
     * @param roomId identifiant de la salle (ex: "!abc:example.com")
     * @param body contenu texte brut
     * @return reçu contenant l'event_id généré par le serveur
     */
    CompletionStage<MessageReceipt> sendText(String roomId, String body);
}