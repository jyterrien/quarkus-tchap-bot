package fr.gouv.tchap.quarkus.events;

import java.time.Instant;

/**
 * Message Tchap publié aux observers CDI.
 *
 * @param threadRootId
 *            identifiant de l'événement racine du thread auquel appartient le
 *            message, ou {@code null} s'il ne fait pas partie d'un thread. La
 *            racine elle-même (le message qui ouvre le thread) a donc
 *            {@code threadRootId == null} : c'est un message ordinaire tant que
 *            personne n'y a répondu dans un thread.
 */
public record TchapMessage(String eventId, String roomId, String sender, String body, Instant timestamp,
		boolean encrypted, String messageType, String threadRootId) {

	/**
	 * Message hors thread (conserve la signature antérieure à la prise en charge
	 * des threads).
	 */
	public TchapMessage(String eventId, String roomId, String sender, String body, Instant timestamp, boolean encrypted,
			String messageType) {
		this(eventId, roomId, sender, body, timestamp, encrypted, messageType, null);
	}

	/** Vrai si le message est une réponse dans un thread. */
	public boolean isInThread() {
		return threadRootId != null;
	}

	/**
	 * Racine du thread dans lequel répondre à ce message : la racine du thread s'il
	 * en fait partie, sinon le message lui-même (la réponse ouvre alors un nouveau
	 * thread). À passer à {@code sendTextInThread} / {@code sendNoticeInThread}.
	 */
	public String replyThreadId() {
		return threadRootId != null ? threadRootId : eventId;
	}
}
