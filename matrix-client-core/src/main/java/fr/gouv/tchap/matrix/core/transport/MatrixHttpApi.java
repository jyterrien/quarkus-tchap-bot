package fr.gouv.tchap.matrix.core.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.gouv.tchap.matrix.auth.TokenProvider;
import fr.gouv.tchap.matrix.core.auth.StaticTokenProvider;
import fr.gouv.tchap.matrix.exception.MatrixServerException;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Adaptateur Matrix au-dessus de HttpTransport. API : sync(), sendMessage(),
 * joinRoom(), joinByIdOrAlias(), leaveRoom(), whoami().
 *
 * Le token d'accès vient d'un {@link TokenProvider}, interrogé à chaque appel.
 * Quand le serveur refuse le token (401 / M_UNKNOWN_TOKEN), la requête est
 * rejouée UNE fois avec le token renouvelé ; un second refus est transmis tel
 * quel.
 */
public class MatrixHttpApi {

	private static final String API_PREFIX = "/_matrix/client/v3";
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final HttpTransport transport;
	private final TokenProvider tokens;

	/** Token fixe : jamais renouvelé. */
	public MatrixHttpApi(HttpTransport transport, String accessToken) {
		this(transport, new StaticTokenProvider(accessToken));
	}

	public MatrixHttpApi(HttpTransport transport, TokenProvider tokens) {
		this.transport = transport;
		this.tokens = tokens;
	}

	/**
	 * Exécute {@code call} avec le token courant ; sur refus du token, renouvelle
	 * puis rejoue une fois.
	 */
	private CompletionStage<String> authed(Function<String, CompletionStage<String>> call) {
		return tokens.accessToken().thenCompose(token -> call.apply(token).exceptionallyCompose(ex -> {
			if (!isTokenRejected(ex)) {
				return CompletableFuture.<String>failedFuture(ex);
			}
			return tokens.renew(token).thenCompose(call);
		}));
	}

	private static boolean isTokenRejected(Throwable ex) {
		Throwable cause = ex;
		while (cause instanceof CompletionException && cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause instanceof MatrixServerException server && server.isTokenRejected();
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
	 * @param initialLimit
	 *            nombre maximal d'événements de timeline par salon renvoyés lors du
	 *            PREMIER sync (sans {@code since}). 0 = aucun historique. Valeur
	 *            négative = pas de limite imposée. Ignoré dès qu'un {@code since}
	 *            est fourni.
	 * @param roomIds
	 *            si non vide, le serveur ne renvoie que ces salons. Ce filtre est
	 *            envoyé à CHAQUE requête, pas seulement la première.
	 */
	public CompletionStage<String> sync(String since, long timeoutMs, int initialLimit, Collection<String> roomIds) {
		StringBuilder path = new StringBuilder(API_PREFIX).append("/sync?timeout=").append(timeoutMs);
		boolean incremental = since != null && !since.isBlank();
		if (incremental) {
			path.append("&since=").append(encode(since));
		}
		String filter = buildFilter(roomIds, incremental ? -1 : initialLimit);
		if (filter != null) {
			path.append("&filter=").append(encode(filter));
		}
		return authed(token -> transport.get(path.toString(), token));
	}

	/**
	 * Filtre /sync : {"room":{"rooms":[...],"timeline":{"limit":N}}} ; null si
	 * aucune contrainte.
	 */
	private static String buildFilter(Collection<String> roomIds, int timelineLimit) {
		ObjectNode room = MAPPER.createObjectNode();
		if (roomIds != null && !roomIds.isEmpty()) {
			ArrayNode rooms = room.putArray("rooms");
			roomIds.forEach(rooms::add);
		}
		if (timelineLimit >= 0) {
			room.putObject("timeline").put("limit", timelineLimit);
		}
		if (room.isEmpty())
			return null;
		return MAPPER.createObjectNode().set("room", room).toString();
	}

	/** PUT /rooms/{roomId}/send/m.room.message/{txnId} */
	public CompletionStage<String> sendMessage(String roomId, String txnId, String jsonBody) {
		String path = API_PREFIX + "/rooms/" + encode(roomId) + "/send/m.room.message/" + encode(txnId);
		return authed(token -> transport.put(path, token, jsonBody));
	}

	/** POST /rooms/{roomId}/join */
	public CompletionStage<String> joinRoom(String roomId) {
		String path = API_PREFIX + "/rooms/" + encode(roomId) + "/join";
		return authed(token -> transport.post(path, token, "{}"));
	}

	/**
	 * POST /join/{roomIdOrAlias} : accepte un identifiant (!id:srv) ou un alias
	 * (#nom:srv). Sans effet si le compte est déjà membre. La réponse contient le
	 * room_id résolu.
	 */
	public CompletionStage<String> joinByIdOrAlias(String roomIdOrAlias) {
		String path = API_PREFIX + "/join/" + encode(roomIdOrAlias);
		return authed(token -> transport.post(path, token, "{}"));
	}

	/** POST /rooms/{roomId}/leave */
	public CompletionStage<String> leaveRoom(String roomId) {
		String path = API_PREFIX + "/rooms/" + encode(roomId) + "/leave";
		return authed(token -> transport.post(path, token, "{}"));
	}

	/** GET /account/whoami : identité (user_id, device_id) associée au token. */
	public CompletionStage<String> whoami() {
		return authed(token -> transport.get(API_PREFIX + "/account/whoami", token));
	}

	private String encode(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}
}
