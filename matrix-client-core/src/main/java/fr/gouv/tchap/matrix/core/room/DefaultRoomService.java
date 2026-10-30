package fr.gouv.tchap.matrix.core.room;

import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.room.RoomService;

import java.util.concurrent.CompletionStage;

public class DefaultRoomService implements RoomService {

    private final MatrixHttpApi api;

    public DefaultRoomService(MatrixHttpApi api) {
        this.api = api;
    }

    @Override
    public CompletionStage<Void> join(String roomId) {
        return api.joinRoom(roomId).thenApply(r -> null);
    }

    @Override
    public CompletionStage<Void> leave(String roomId) {
        return api.leaveRoom(roomId).thenApply(r -> null);
    }
}
