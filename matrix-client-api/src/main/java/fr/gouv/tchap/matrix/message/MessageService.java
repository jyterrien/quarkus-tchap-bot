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

    CompletionStage<MessageReceipt> sendNotice(String roomId, String body);

    /**
     * Envoie un message texte dans un thread de discussion (relation {@code m.thread}).
     *
     * <p>{@code threadRootId} est l'événement racine du thread, jamais une réponse : pour répondre
     * dans le thread d'un événement reçu, utiliser {@code MatrixEvent#threadRootId()}. Pour ouvrir
     * un nouveau thread, passer l'identifiant du message de départ (qui n'est pas déjà dans un thread).
     *
     * <p>Le message porte aussi une relation de réponse de repli ({@code is_falling_back}) vers la
     * racine, afin que les clients sans gestion des threads l'affichent comme une réponse classique.
     *
     * @param threadRootId event_id de la racine du thread (ex: "$abc")
     * @throws IllegalArgumentException si {@code threadRootId} est nul ou vide
     */
    CompletionStage<MessageReceipt> sendTextInThread(String roomId, String threadRootId, String body);

    /** Comme {@link #sendTextInThread}, avec le msgtype {@code m.notice}. */
    CompletionStage<MessageReceipt> sendNoticeInThread(String roomId, String threadRootId, String body);
}
