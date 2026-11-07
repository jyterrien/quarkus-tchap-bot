package fr.gouv.tchap.matrix.core.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.message.MessageReceipt;
import fr.gouv.tchap.matrix.message.MessageService;
import fr.gouv.tchap.matrix.message.TransactionIdGenerator;

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
        return send(roomId, "m.text", body, null);
    }

    @Override
    public CompletionStage<MessageReceipt> sendNotice(String roomId, String body) {
        return send(roomId, "m.notice", body, null);
    }

    @Override
    public CompletionStage<MessageReceipt> sendTextInThread(String roomId, String threadRootId, String body) {
        return send(roomId, "m.text", body, requireThreadRoot(threadRootId));
    }

    @Override
    public CompletionStage<MessageReceipt> sendNoticeInThread(String roomId, String threadRootId, String body) {
        return send(roomId, "m.notice", body, requireThreadRoot(threadRootId));
    }

    /** @param threadRootId racine du thread, ou null pour un message hors thread */
    private CompletionStage<MessageReceipt> send(String roomId, String msgtype, String body, String threadRootId) {
        String txnId = txnGenerator.next();
        ObjectNode content = MAPPER.createObjectNode()
            .put("msgtype", msgtype)
            .put("body", body);
        if (threadRootId != null) {
            addThreadRelation(content, threadRootId);
        }
        return api.sendMessage(roomId, txnId, content.toString())
            .thenApply(response -> new MessageReceipt(extractEventId(response)));
    }

    /**
     * Relation m.thread (Matrix 1.4). La réponse de repli pointe vers la racine, faute de connaître
     * le dernier message du thread : les clients sans threads l'affichent comme une réponse à la racine.
     */
    private static void addThreadRelation(ObjectNode content, String threadRootId) {
        ObjectNode relatesTo = content.putObject("m.relates_to");
        relatesTo.put("rel_type", "m.thread");
        relatesTo.put("event_id", threadRootId);
        relatesTo.put("is_falling_back", true);
        relatesTo.putObject("m.in_reply_to").put("event_id", threadRootId);
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
