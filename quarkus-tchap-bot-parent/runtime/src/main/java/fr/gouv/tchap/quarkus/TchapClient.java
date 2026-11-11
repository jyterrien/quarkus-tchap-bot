package fr.gouv.tchap.quarkus;

import fr.gouv.tchap.matrix.client.MatrixClient;
import fr.gouv.tchap.matrix.message.MessageReceipt;
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
}
