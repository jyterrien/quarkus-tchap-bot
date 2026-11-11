package fr.gouv.tchap.matrix.event;

import java.time.Instant;

/**
 * Événement Matrix brut, tel que reçu par /sync. Les champs correspondent aux
 * propriétés communes de tous les événements.
 *
 * @param threadRootId
 *            identifiant de l'événement racine du thread auquel appartient cet
 *            événement (relation {@code m.relates_to} de type
 *            {@code m.thread}), ou {@code null} s'il ne fait pas partie d'un
 *            thread. Pour répondre dans le même thread, le passer à
 *            {@code sendTextInThread}.
 */
public record MatrixEvent(String eventId, String roomId, String sender, String type, // ex: "m.room.message"
		String content, // JSON brut du contenu
		Instant timestamp, // origin_server_ts converti
		String threadRootId // racine du thread, null hors thread
) {

	/**
	 * Événement hors thread (conserve la signature antérieure à la prise en charge
	 * des threads).
	 */
	public MatrixEvent(String eventId, String roomId, String sender, String type, String content, Instant timestamp) {
		this(eventId, roomId, sender, type, content, timestamp, null);
	}

	/** Vrai si l'événement est une réponse dans un thread. */
	public boolean isInThread() {
		return threadRootId != null;
	}
}
