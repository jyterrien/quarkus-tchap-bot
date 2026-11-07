package fr.gouv.tchap.matrix.room;

import fr.gouv.tchap.matrix.message.MessageReceipt;

import java.util.concurrent.CompletionStage;

/**
 * Accès à UN salon Matrix : tout ce qui est envoyé par cet objet l'est dans ce salon,
 * et dans aucun autre. {@link #roomId()} est l'identifiant résolu (jamais un alias).
 */
public interface MatrixRoom {

    String roomId();

    /** Envoie un message texte (msgtype m.text). */
    CompletionStage<MessageReceipt> sendText(String body);

    /**
     * Envoie une notice (msgtype m.notice) : message automatique, que les clients
     * n'affichent pas comme une intervention humaine et auquel les bots ne répondent pas.
     */
    CompletionStage<MessageReceipt> sendNotice(String body);

    /**
     * Envoie un message texte dans un thread de ce salon. {@code threadRootId} est l'événement
     * racine du thread ; voir {@link fr.gouv.tchap.matrix.message.MessageService#sendTextInThread}.
     *
     * @throws IllegalArgumentException si {@code threadRootId} est nul ou vide
     */
    CompletionStage<MessageReceipt> sendTextInThread(String threadRootId, String body);

    /** Comme {@link #sendTextInThread}, avec le msgtype {@code m.notice}. */
    CompletionStage<MessageReceipt> sendNoticeInThread(String threadRootId, String body);
}
