package fr.gouv.tchap.matrix.core.message;

import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.message.MessageReceipt;
import fr.gouv.tchap.matrix.message.MessageService;
import fr.gouv.tchap.matrix.message.TransactionIdGenerator;

import java.util.concurrent.CompletionStage;

public class DefaultMessageService implements MessageService {

    private final MatrixHttpApi api;
    private final TransactionIdGenerator txnGenerator;

    public DefaultMessageService(MatrixHttpApi api, TransactionIdGenerator txnGenerator) {
        this.api = api;
        this.txnGenerator = txnGenerator;
    }

    @Override
    public CompletionStage<MessageReceipt> sendText(String roomId, String body) {
        String txnId = txnGenerator.next();
        String json = "{\"msgtype\":\"m.text\",\"body\":\"" + escapeJson(body) + "\"}";
        return api.sendMessage(roomId, txnId, json)
            .thenApply(response -> new MessageReceipt(extractField(response, "event_id")));
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String extractField(String json, String field) {
        String pattern = "\"" + field + "\":\"";
        int start = json.indexOf(pattern);
        if (start < 0) return "";
        start += pattern.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }
}
