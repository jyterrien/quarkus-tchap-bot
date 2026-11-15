package fr.gouv.tchap.matrix.core.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.message.MessageReceipt;
import fr.gouv.tchap.matrix.message.MessageService;
import fr.gouv.tchap.matrix.message.OutgoingMessage;
import fr.gouv.tchap.matrix.message.TransactionIdGenerator;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public class DefaultMessageService implements MessageService {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final MatrixHttpApi api;
	private final TransactionIdGenerator txnGenerator;

	public DefaultMessageService(MatrixHttpApi api, TransactionIdGenerator txnGenerator) {
		this.api = api;
		this.txnGenerator = txnGenerator;
	}

	@Override
	public CompletionStage<MessageReceipt> sendText(String roomId, String body) {
		return send(roomId, OutgoingMessage.text(body));
	}

	@Override
	public CompletionStage<MessageReceipt> sendNotice(String roomId, String body) {
		return send(roomId, OutgoingMessage.notice(body));
	}

	@Override
	public CompletionStage<MessageReceipt> sendTextInThread(String roomId, String threadRootId, String body) {
		return send(roomId, OutgoingMessage.text(body).inThread(requireThreadRoot(threadRootId)));
	}

	@Override
	public CompletionStage<MessageReceipt> sendNoticeInThread(String roomId, String threadRootId, String body) {
		return send(roomId, OutgoingMessage.notice(body).inThread(requireThreadRoot(threadRootId)));
	}

	@Override
	public CompletionStage<MessageReceipt> send(String roomId, OutgoingMessage message) {
		Objects.requireNonNull(message, "message is required");
		String txnId = txnGenerator.next();
		return api.sendMessage(roomId, txnId, toContent(message).toString())
				.thenApply(response -> new MessageReceipt(extractEventId(response)));
	}

	/**
	 * Contenu {@code m.room.message}. Ordre des champs : msgtype, body, format et
	 * formatted_body, puis m.relates_to.
	 */
	static ObjectNode toContent(OutgoingMessage message) {
		ObjectNode content = MAPPER.createObjectNode().put("msgtype", message.type().msgtype()).put("body",
				message.body());
		if (message.formattedBody() != null) {
			content.put("format", "org.matrix.custom.html").put("formatted_body", message.formattedBody());
		}
		addRelation(content, message);
		return content;
	}

	/**
	 * Relations (Matrix 1.4) :
	 * <ul>
	 * <li>réponse simple : {@code m.in_reply_to} seul ;</li>
	 * <li>thread seul : {@code m.thread} avec une réponse de repli
	 * ({@code is_falling_back: true}) vers la racine, faute de connaître le dernier
	 * message du thread : les clients sans threads l'affichent comme une réponse à
	 * la racine ;</li>
	 * <li>réponse dans un thread : {@code m.thread} avec {@code m.in_reply_to}
	 * pointant vraiment le message visé ({@code is_falling_back: false}).</li>
	 * </ul>
	 */
	private static void addRelation(ObjectNode content, OutgoingMessage message) {
		String root = message.threadRootId();
		String replyTo = message.replyToEventId();
		if (root == null && replyTo == null) {
			return;
		}
		ObjectNode relatesTo = content.putObject("m.relates_to");
		if (root == null) {
			relatesTo.putObject("m.in_reply_to").put("event_id", replyTo);
			return;
		}
		relatesTo.put("rel_type", "m.thread");
		relatesTo.put("event_id", root);
		relatesTo.put("is_falling_back", replyTo == null);
		relatesTo.putObject("m.in_reply_to").put("event_id", replyTo == null ? root : replyTo);
	}

	private static String requireThreadRoot(String threadRootId) {
		if (threadRootId == null || threadRootId.isBlank()) {
			throw new IllegalArgumentException("threadRootId is required");
		}
		return threadRootId;
	}

	private static String extractEventId(String response) {
		try {
			JsonNode eventId = MAPPER.readTree(response).path("event_id");
			if (!eventId.isTextual() || eventId.asText().isBlank()) {
				throw new MatrixException("Missing event_id in send response: " + response);
			}
			return eventId.asText();
		} catch (MatrixException e) {
			throw e;
		} catch (Exception e) {
			throw new MatrixException("Invalid send response: " + response, e);
		}
	}
}
