package fr.gouv.tchap.matrix.core.room;

import fr.gouv.tchap.matrix.message.MessageReceipt;
import fr.gouv.tchap.matrix.message.MessageService;
import fr.gouv.tchap.matrix.room.MatrixRoom;

import java.util.concurrent.CompletionStage;

/** Vue d'un MessageService restreinte à un seul salon. */
public final class DefaultMatrixRoom implements MatrixRoom {

    private final String roomId;
    private final MessageService messages;

    public DefaultMatrixRoom(String roomId, MessageService messages) {
        if (roomId == null || roomId.isBlank()) {
            throw new IllegalArgumentException("roomId is required");
        }
        this.roomId = roomId;
        this.messages = messages;
    }

    @Override
    public String roomId() {
        return roomId;
    }

    @Override
    public CompletionStage<MessageReceipt> sendText(String body) {
        return messages.sendText(roomId, body);
    }

    @Override
    public CompletionStage<MessageReceipt> sendNotice(String body) {
        return messages.sendNotice(roomId, body);
    }

    @Override
    public String toString() {
        return "MatrixRoom[" + roomId + "]";
    }
}
