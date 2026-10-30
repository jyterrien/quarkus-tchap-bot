package fr.gouv.tchap.matrix.room;

import java.util.concurrent.CompletionStage;

public interface RoomService {
    CompletionStage<Void> join(String roomId);
    CompletionStage<Void> leave(String roomId);
}