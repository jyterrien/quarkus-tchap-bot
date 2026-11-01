package fr.gouv.tchap.matrix.core.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.gouv.tchap.matrix.transport.HttpTransport;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Adaptateur Matrix au-dessus de HttpTransport.
 * API : sync(), sendMessage(), joinRoom(), joinByIdOrAlias(), leaveRoom(), whoami().
 */
public class MatrixHttpApi {

    private static final String API_PREFIX = "/_matrix/client/v3";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpTransport transport;
    private final String accessToken;

    public MatrixHttpApi(HttpTransport transport, String accessToken) {
        this.transport = transport;
        this.accessToken = accessToken;
    }

    /** GET /sync, sans filtre. */
    public CompletionStage<String> sync(String since, long timeoutMs) {
        return sync(since, timeoutMs, -1);
    }

    /** GET /sync, filtre d'historique initial seulement. */
    public CompletionStage<String> sync(String since, long timeoutMs, int initialLimit) {
        return sync(since, timeoutMs, initialLimit, List.of());
    }

    /**
     * GET /sync.
     *
     * @param initialLimit nombre maximal d'événements de timeline par salon renvoyés
     *                     lors du PREMIER sync (sans {@code since}). 0 = aucun historique.
     *                     Valeur négative = pas de limite imposée.
     *                     Ignoré dès qu'un {@code since} est fourni.
     * @param roomIds      si non vide, le serveur ne renvoie que ces salons.
     *                     Ce filtre est envoyé à CHAQUE requête, pas seulement la première.
     */
    public CompletionStage<String> sync(String since, long timeoutMs, int initialLimit,
                                        Collection<String> roomIds) {
        StringBuilder path = new StringBuilder(API_PREFIX)
            .append("/sync?timeout=").append(timeoutMs);
        boolean incremental = since != null && !since.isBlank();
        if (incremental) {
            path.append("&since=").append(encode(since));
        }
        String filter = buildFilter(roomIds, incremental ? -1 : initialLimit);
        if (filter != null) {
            path.append("&filter=").append(encode(filter));
        }
        return transport.get(path.toString(), accessToken);
    }

    /** Filtre /sync : {"room":{"rooms":[...],"timeline":{"limit":N}}} ; null si aucune contrainte. */
    private static String buildFilter(Collection<String> roomIds, int timelineLimit) {
        ObjectNode room = MAPPER.createObjectNode();
        if (roomIds != null && !roomIds.isEmpty()) {
            ArrayNode rooms = room.putArray("rooms");
            roomIds.forEach(rooms::add);
        }
        if (timelineLimit >= 0) {
            room.putObject("timeline").put("limit", timelineLimit);
        }
        if (room.isEmpty()) return null;
        return MAPPER.createObjectNode().set("room", room).toString();
    }

    /** PUT /rooms/{roomId}/send/m.room.message/{txnId} */
    public CompletionStage<String> sendMessage(String roomId, String txnId, String jsonBody) {
        String path = API_PREFIX + "/rooms/" + encode(roomId)
            + "/send/m.room.message/" + encode(txnId);
        return transport.put(path, accessToken, jsonBody);
    }

    /** POST /rooms/{roomId}/join */
    public CompletionStage<String> joinRoom(String roomId) {
        String path = API_PREFIX + "/rooms/" + encode(roomId) + "/join";
        return transport.post(path, accessToken, "{}");
    }

    /**
     * POST /join/{roomIdOrAlias} : accepte un identifiant (!id:srv) ou un alias (#nom:srv).
     * Sans effet si le compte est déjà membre. La réponse contient le room_id résolu.
     */
    public CompletionStage<String> joinByIdOrAlias(String roomIdOrAlias) {
        String path = API_PREFIX + "/join/" + encode(roomIdOrAlias);
        return transport.post(path, accessToken, "{}");
    }

    /** POST /rooms/{roomId}/leave */
    public CompletionStage<String> leaveRoom(String roomId) {
        String path = API_PREFIX + "/rooms/" + encode(roomId) + "/leave";
        return transport.post(path, accessToken, "{}");
    }

    /** GET /account/whoami : identité (user_id, device_id) associée au token. */
    public CompletionStage<String> whoami() {
        return transport.get(API_PREFIX + "/account/whoami", accessToken);
    }

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
