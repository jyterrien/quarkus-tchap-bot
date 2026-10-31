package fr.gouv.tchap.matrix.core.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.gouv.tchap.matrix.event.MatrixEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Parse la réponse /sync et extrait les événements timeline et les invitations.
 * Livraison at-least-once : la déduplication par event_id est faite par SyncEngine.
 */
public class SyncEventParser {

    private final ObjectMapper mapper;

    public SyncEventParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String extractNextBatch(String json) {
        try {
            return mapper.readTree(json).path("next_batch").asText(null);
        } catch (Exception e) {
            throw new RuntimeException("Invalid /sync response", e);
        }
    }

    /** Identifiants des salons où l'utilisateur est invité (rooms.invite). */
    public List<String> parseInvitedRoomIds(String json) {
        try {
            List<String> roomIds = new ArrayList<>();
            mapper.readTree(json).path("rooms").path("invite").fieldNames().forEachRemaining(roomIds::add);
            return roomIds;
        } catch (Exception e) {
            throw new RuntimeException("Invalid /sync response", e);
        }
    }

    public List<MatrixEvent> parseEvents(String json) {
        List<MatrixEvent> events = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode join = root.path("rooms").path("join");
            if (join.isMissingNode()) return events;

            join.fields().forEachRemaining(roomEntry -> {
                String roomId = roomEntry.getKey();
                JsonNode timeline = roomEntry.getValue().path("timeline").path("events");
                if (!timeline.isArray()) return;
                for (JsonNode ev : timeline) {
                    String eventId = ev.path("event_id").asText(null);
                    if (eventId == null) continue;
                    String sender = ev.path("sender").asText(null);
                    String type = ev.path("type").asText(null);
                    long ts = ev.path("origin_server_ts").asLong(0L);
                    String content = ev.path("content").toString();
                    events.add(new MatrixEvent(
                        eventId,
                        roomId,
                        sender,
                        type,
                        content,
                        Instant.ofEpochMilli(ts)
                    ));
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("Invalid /sync response", e);
        }
        return events;
    }
}
