package fr.gouv.tchap.quarkus.events;

import fr.gouv.tchap.matrix.message.OutgoingMessage;
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
 * @param replyToEventId
 *            identifiant du message auquel celui-ci répond précisément, ou
 *            {@code null} si ce n'est pas une réponse. La réponse de repli que
 *            les clients ajoutent dans un thread n'en est pas une.
 */
public record TchapMessage(String eventId, String roomId, String sender, String body, Instant timestamp,
		boolean encrypted, String messageType, String threadRootId, String replyToEventId) {

	/**
	 * Message hors thread et sans réponse (conserve la signature antérieure à la
	 * prise en charge des threads).
	 */
	public TchapMessage(String eventId, String roomId, String sender, String body, Instant timestamp, boolean encrypted,
			String messageType) {
		this(eventId, roomId, sender, body, timestamp, encrypted, messageType, null, null);
	}

	/**
	 * Message sans réponse (signature antérieure à la prise en charge des
	 * réponses).
	 */
	public TchapMessage(String eventId, String roomId, String sender, String body, Instant timestamp, boolean encrypted,
			String messageType, String threadRootId) {
		this(eventId, roomId, sender, body, timestamp, encrypted, messageType, threadRootId, null);
	}

	/** Vrai si le message répond précisément à un autre message. */
	public boolean isReply() {
		return replyToEventId != null;
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

	/**
	 * Réponse ciblée à ce message (texte brut), à envoyer avec {@code send}. Si le
	 * message est dans un thread, la réponse y reste ; sinon c'est une réponse
	 * simple, qui n'ouvre pas de thread. Pour une notice ou une version HTML :
	 * {@code message.reply("ok").withType(Type.NOTICE).withHtml("<b>ok</b>")}.
	 */
	public OutgoingMessage reply(String text) {
		OutgoingMessage reply = OutgoingMessage.text(text).inReplyTo(eventId);
		return threadRootId != null ? reply.inThread(threadRootId) : reply;
	}
}
