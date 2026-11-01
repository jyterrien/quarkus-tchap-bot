package fr.gouv.tchap.matrix.core.room;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.room.RoomService;

import java.util.concurrent.CompletionStage;

public class DefaultRoomService implements RoomService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    @Override
    public CompletionStage<String> resolveAndJoin(String roomIdOrAlias) {
        return api.joinByIdOrAlias(roomIdOrAlias)
            .thenApply(response -> extractRoomId(roomIdOrAlias, response));
    }

    private static String extractRoomId(String requested, String response) {
        try {
            JsonNode roomId = MAPPER.readTree(response).path("room_id");
            if (!roomId.isTextual() || roomId.asText().isBlank()) {
                throw new MatrixException("Missing room_id in join response for " + requested + ": " + response);
            }
            return roomId.asText();
        } catch (MatrixException e) {
            throw e;
        } catch (Exception e) {
            throw new MatrixException("Invalid join response for " + requested + ": " + response, e);
        }
    }
}
