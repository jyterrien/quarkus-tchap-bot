package fr.gouv.tchap.matrix.core.transport;

import fr.gouv.tchap.matrix.transport.HttpTransport;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionStage;

/**
 * Adaptateur Matrix au-dessus de HttpTransport.
 * API : sync(), sendMessage(), joinRoom(), leaveRoom().
 */
public class MatrixHttpApi {

    private static final String API_PREFIX = "/_matrix/client/v3";

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

    /**
     * GET /sync.
     *
     * @param initialLimit nombre maximal d'événements de timeline par salon renvoyés
     *                     lors du PREMIER sync (sans {@code since}). 0 = aucun historique.
     *                     Valeur négative = pas de filtre (comportement par défaut du serveur).
     *                     Ignoré dès qu'un {@code since} est fourni.
     */
    public CompletionStage<String> sync(String since, long timeoutMs, int initialLimit) {
        StringBuilder path = new StringBuilder(API_PREFIX)
            .append("/sync?timeout=").append(timeoutMs);
        boolean incremental = since != null && !since.isBlank();
        if (incremental) {
            path.append("&since=").append(encode(since));
        } else if (initialLimit >= 0) {
            String filter = "{\"room\":{\"timeline\":{\"limit\":" + initialLimit + "}}}";
            path.append("&filter=").append(encode(filter));
        }
        return transport.get(path.toString(), accessToken);
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

    /** POST /rooms/{roomId}/leave */
    public CompletionStage<String> leaveRoom(String roomId) {
        String path = API_PREFIX + "/rooms/" + encode(roomId) + "/leave";
        return transport.post(path, accessToken, "{}");
    }

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
