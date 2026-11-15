package fr.gouv.tchap.quarkus.events;

import java.time.Instant;

/**
 * Un message déjà publié a été modifié par son auteur. Événement CDI distinct
 * de {@link TchapMessage} : une édition n'est PAS republiée comme un nouveau
 * message, sinon un bot qui répond à chaque message répondrait aussi à chaque
 * correction.
 *
 * @param eventId
 *            identifiant de l'événement d'édition lui-même
 * @param replacesEventId
 *            identifiant du message d'origine qui est remplacé
 * @param body
 *            nouveau texte (sans le préfixe « * » des clients de repli)
 * @param messageType
 *            nouveau {@code msgtype}
 */
public record TchapMessageEdited(String eventId, String roomId, String sender, String replacesEventId, String body,
		Instant timestamp, String messageType) {
}
