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
}
