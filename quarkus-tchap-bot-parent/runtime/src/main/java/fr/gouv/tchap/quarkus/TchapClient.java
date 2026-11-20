package fr.gouv.tchap.quarkus;

import fr.gouv.tchap.matrix.client.MatrixClient;
import fr.gouv.tchap.matrix.media.MediaContent;
import fr.gouv.tchap.matrix.media.MxcUri;
import fr.gouv.tchap.matrix.message.MessageReceipt;
import fr.gouv.tchap.matrix.message.OutgoingMessage;
import fr.gouv.tchap.quarkus.events.TchapMedia;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class TchapClient {

	@Inject
	MatrixClient matrixClient;

	public CompletionStage<MessageReceipt> sendText(String roomId, String body) {
		return matrixClient.messages().sendText(roomId, body);
	}

	/**
	 * Envoie un message texte dans un thread. {@code threadRootId} est l'événement
	 * racine du thread ; pour répondre à un message reçu, utiliser
	 * {@code TchapMessage#replyThreadId()}.
	 *
	 * @throws IllegalArgumentException
	 *             si {@code threadRootId} est nul ou vide
	 */
	public CompletionStage<MessageReceipt> sendTextInThread(String roomId, String threadRootId, String body) {
		return matrixClient.messages().sendTextInThread(roomId, threadRootId, body);
	}

	/** Comme {@link #sendTextInThread}, avec le msgtype {@code m.notice}. */
	public CompletionStage<MessageReceipt> sendNoticeInThread(String roomId, String threadRootId, String body) {
		return matrixClient.messages().sendNoticeInThread(roomId, threadRootId, body);
	}

	/**
	 * Envoie un message complet : type (texte, notice, emote), version HTML,
	 * réponse à un message précis, thread. Voir {@link OutgoingMessage}.
	 */
	public CompletionStage<MessageReceipt> send(String roomId, OutgoingMessage message) {
		return matrixClient.messages().send(roomId, message);
	}

	/**
	 * Téléverse un fichier et rend son adresse {@code mxc://}. Voir
	 * {@link fr.gouv.tchap.matrix.media.MediaService#upload}.
	 */
	public CompletionStage<MxcUri> upload(byte[] content, String contentType, String filename) {
		return matrixClient.media().upload(content, contentType, filename);
	}

	/**
	 * Télécharge le média d'un message reçu. Le média est lu en entier en mémoire.
	 */
	public CompletionStage<MediaContent> download(TchapMedia media) {
		return matrixClient.media().download(media.uri());
	}

	/** Télécharge un média par son adresse {@code mxc://}. */
	public CompletionStage<MediaContent> download(MxcUri uri) {
		return matrixClient.media().download(uri);
	}

	/**
	 * Téléverse un fichier puis l'envoie dans le salon : image, audio, vidéo ou
	 * fichier selon {@code contentType}. {@code caption} est le texte affiché avec
	 * le média ; {@code null} pour le nom du fichier.
	 */
	public CompletionStage<MessageReceipt> sendMedia(String roomId, byte[] content, String contentType, String filename,
			String caption) {
		return matrixClient.media().prepareMessage(content, contentType, filename, caption)
				.thenCompose(message -> matrixClient.messages().send(roomId, message));
	}

	/**
	 * Modifie un message que le bot a envoyé (le nouveau contenu ne peut pas
	 * changer sa réponse ni son thread). Voir
	 * {@link fr.gouv.tchap.matrix.message.MessageService#edit}.
	 */
	public CompletionStage<MessageReceipt> edit(String roomId, String eventId, OutgoingMessage newContent) {
		return matrixClient.messages().edit(roomId, eventId, newContent);
	}

	/**
	 * Réagit à un message avec {@code key} (en général un emoji). Voir
	 * {@link fr.gouv.tchap.matrix.message.MessageService#react}.
	 */
	public CompletionStage<MessageReceipt> react(String roomId, String eventId, String key) {
		return matrixClient.messages().react(roomId, eventId, key);
	}
}
