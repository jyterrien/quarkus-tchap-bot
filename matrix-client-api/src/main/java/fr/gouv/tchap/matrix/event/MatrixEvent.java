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
 * @param replyToEventId
 *            identifiant de l'événement auquel celui-ci répond précisément
 *            ({@code m.in_reply_to}), ou {@code null}. Une réponse de repli
 *            ajoutée par un client dans un thread ({@code is_falling_back})
 *            n'est pas une vraie réponse : elle donne {@code null}.
 * @param replacesEventId
 *            identifiant du message que cet événement remplace
 *            ({@code m.relates_to} de type {@code m.replace}, c'est-à-dire une
 *            édition), ou {@code null}. Le contenu du message modifié est dans
 *            {@code content.m.new_content} ; {@code content.body} n'en est
 *            qu'une version de repli préfixée de « * ».
 */
public record MatrixEvent(String eventId, String roomId, String sender, String type, // ex: "m.room.message"
		String content, // JSON brut du contenu
		Instant timestamp, // origin_server_ts converti
		String threadRootId, // racine du thread, null hors thread
		String replyToEventId, // événement visé par la réponse, null si ce n'en est pas une
		String replacesEventId // message remplacé par cette édition, null si ce n'en est pas une
) {

	/**
	 * Événement hors thread et sans réponse (conserve la signature antérieure à la
	 * prise en charge des threads).
	 */
	public MatrixEvent(String eventId, String roomId, String sender, String type, String content, Instant timestamp) {
		this(eventId, roomId, sender, type, content, timestamp, null, null, null);
	}

	/**
	 * Événement sans réponse (signature antérieure à la prise en charge des
	 * réponses).
	 */
	public MatrixEvent(String eventId, String roomId, String sender, String type, String content, Instant timestamp,
			String threadRootId) {
		this(eventId, roomId, sender, type, content, timestamp, threadRootId, null, null);
	}

	/**
	 * Événement qui n'est pas une édition (signature antérieure à leur prise en
	 * charge).
	 */
	public MatrixEvent(String eventId, String roomId, String sender, String type, String content, Instant timestamp,
			String threadRootId, String replyToEventId) {
		this(eventId, roomId, sender, type, content, timestamp, threadRootId, replyToEventId, null);
	}

	/** Vrai si l'événement est une édition d'un message précédent. */
	public boolean isEdit() {
		return replacesEventId != null;
	}

	/** Vrai si l'événement répond précisément à un autre message. */
	public boolean isReply() {
		return replyToEventId != null;
	}

	/** Vrai si l'événement est une réponse dans un thread. */
	public boolean isInThread() {
		return threadRootId != null;
	}
}
