package fr.gouv.tchap.matrix.message;

/** Reçu d'un envoi réussi. Correspond au champ "event_id" de la réponse. */
public record MessageReceipt(String eventId) {
}
