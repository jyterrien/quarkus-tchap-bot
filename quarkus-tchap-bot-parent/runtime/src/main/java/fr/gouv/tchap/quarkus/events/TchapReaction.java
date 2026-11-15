package fr.gouv.tchap.quarkus.events;

import java.time.Instant;

/**
 * Quelqu'un a réagi à un message (relation {@code m.annotation}), en général
 * avec un emoji.
 *
 * @param eventId
 *            identifiant de l'événement de réaction
 * @param reactedEventId
 *            identifiant du message auquel on a réagi
 * @param key
 *            la réaction, ex. {@code "👍"}
 */
public record TchapReaction(String eventId, String roomId, String sender, String reactedEventId, String key,
		Instant timestamp) {
}
