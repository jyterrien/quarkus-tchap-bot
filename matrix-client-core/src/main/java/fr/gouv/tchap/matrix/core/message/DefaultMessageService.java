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
        String txnId = txnGenerator.next();
        ObjectNode content = MAPPER.createObjectNode()
            .put("msgtype", "m.text")
            .put("body", body);
        return api.sendMessage(roomId, txnId, content.toString())
            .thenApply(response -> new MessageReceipt(extractEventId(response)));
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
