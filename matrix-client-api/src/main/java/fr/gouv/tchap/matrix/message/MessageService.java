package fr.gouv.tchap.matrix.message;

import java.util.concurrent.CompletionStage;

public interface MessageService {
	/**
	 * Envoie un message texte simple dans une salle.
	 *
	 * @param roomId
	 *            identifiant de la salle (ex: "!abc:example.com")
	 * @param body
	 *            contenu texte brut
	 *
	 * @return reçu contenant l'event_id généré par le serveur
	 */
	CompletionStage<MessageReceipt> sendText(String roomId, String body);

	CompletionStage<MessageReceipt> sendNotice(String roomId, String body);

	/**
	 * Envoie un message texte dans un thread de discussion (relation
	 * {@code m.thread}).
	 * <p>
	 * {@code threadRootId} est l'événement racine du thread, jamais une réponse :
	 * pour répondre dans le thread d'un événement reçu, utiliser
	 * {@code MatrixEvent#threadRootId()}. Pour ouvrir un nouveau thread, passer
	 * l'identifiant du message de départ (qui n'est pas déjà dans un thread).
	 * <p>
	 * Le message porte aussi une relation de réponse de repli
	 * ({@code is_falling_back}) vers la racine, afin que les clients sans gestion
	 * des threads l'affichent comme une réponse classique.
	 *
	 * @param threadRootId
	 *            event_id de la racine du thread (ex: "$abc")
	 *
	 * @throws IllegalArgumentException
	 *             si {@code threadRootId} est nul ou vide
	 */
	CompletionStage<MessageReceipt> sendTextInThread(String roomId, String threadRootId, String body);

	/** Comme {@link #sendTextInThread}, avec le msgtype {@code m.notice}. */
	CompletionStage<MessageReceipt> sendNoticeInThread(String roomId, String threadRootId, String body);

	/**
	 * Envoie un message complet : type ({@code m.text}, {@code m.notice},
	 * {@code m.emote}), version HTML, réponse à un message précis, thread. C'est le
	 * point d'entrée à utiliser dès qu'on dépasse le texte simple ; voir
	 * {@link OutgoingMessage}.
	 * <p>
	 * Les réponses n'embarquent pas de « citation de repli » dans le texte (retirée
	 * de la spécification Matrix 1.13) : les clients affichent la citation à partir
	 * de la relation {@code m.in_reply_to}.
	 *
	 * @throws NullPointerException
	 *             si {@code message} est nul
	 */
	CompletionStage<MessageReceipt> send(String roomId, OutgoingMessage message);

	/**
	 * Modifie un message déjà envoyé (relation {@code m.replace}). Seul l'auteur
	 * d'un message peut le modifier : modifier le message d'un autre est accepté
	 * par le serveur mais ignoré par les clients.
	 * <p>
	 * {@code newContent} donne le nouveau texte, le type et la version HTML ; ses
	 * relations ne peuvent pas changer : une réponse ou un thread sont refusés
	 * ({@link IllegalArgumentException}). Les clients qui ne gèrent pas les
	 * éditions affichent le texte de repli « * nouveau texte ».
	 *
	 * @param eventId
	 *            message à modifier (sa version d'origine, pas une édition)
	 *
	 * @return reçu de l'événement d'édition (pas celui du message d'origine)
	 */
	CompletionStage<MessageReceipt> edit(String roomId, String eventId, OutgoingMessage newContent);

	/**
	 * Réagit à un message (relation {@code m.annotation}) avec {@code key}, en
	 * général un emoji (ex. {@code "👍"}).
	 * <p>
	 * Réagir deux fois avec la même clé au même message est refusé par le serveur
	 * (réaction en double). Pour retirer une réaction, supprimer son événement avec
	 * {@link #redact} : son identifiant est le reçu rendu ici.
	 *
	 * @throws IllegalArgumentException
	 *             si {@code eventId} ou {@code key} est nul ou vide
	 */
	CompletionStage<MessageReceipt> react(String roomId, String eventId, String key);

	/**
	 * Supprime un événement ({@code PUT /rooms/{roomId}/redact/{eventId}/{txnId}})
	 * : un message, mais aussi une réaction. Pour retirer une réaction, supprimer
	 * l'événement de réaction, dont l'identifiant est le reçu rendu par
	 * {@link #react}.
	 * <p>
	 * Le contenu de l'événement est effacé chez tout le monde ; il reste dans le
	 * salon sous forme d'un événement vide. Le bot peut supprimer ses propres
	 * événements ; supprimer ceux des autres demande le droit de modération du
	 * salon (sinon {@code MatrixServerException} 403, {@code M_FORBIDDEN}).
	 *
	 * @param reason
	 *            motif, visible des membres du salon ; {@code null} pour aucun
	 *
	 * @return reçu de l'événement de suppression (pas de l'événement supprimé)
	 *
	 * @throws IllegalArgumentException
	 *             si {@code eventId} est nul ou vide
	 */
	CompletionStage<MessageReceipt> redact(String roomId, String eventId, String reason);
}
