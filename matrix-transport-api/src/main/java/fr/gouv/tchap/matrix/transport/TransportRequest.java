package fr.gouv.tchap.matrix.transport;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Requête HTTP générique à envoyer avec {@link HttpTransport#send}. Immuable :
 * les méthodes {@code with*} renvoient une copie.
 *
 * <pre>
 * TransportRequest.of("DELETE", "/_matrix/client/v3/directory/room/%23alias%3Asrv", token)
 * TransportRequest.of("POST", "/_matrix/media/v3/upload?filename=a.png", token)
 *         .withBody("image/png", bytes).withTimeout(Duration.ofMinutes(2))
 * </pre>
 *
 * Le jeton d'accès a son propre champ et {@code Content-Type} le sien : les
 * mettre dans les en-têtes est refusé, pour qu'il n'y ait qu'une manière de les
 * fixer. {@code accessToken} nul ou vide : pas d'en-tête Authorization.
 *
 * @param method
 *            GET, POST, PUT, DELETE ou PATCH (insensible à la casse)
 * @param path
 *            chemin absolu à partir de la racine du homeserver, avec sa query
 *            string éventuelle (déjà encodés)
 * @param headers
 *            en-têtes supplémentaires (User-Agent, X-Request-ID...), ni
 *            Authorization ni Content-Type
 * @param contentType
 *            type du corps, obligatoire avec un corps
 * @param body
 *            corps binaire, ou {@code null}
 * @param timeout
 *            délai maximal de la requête, qui remplace celui du transport ;
 *            {@code null} pour garder celui du transport
 */
public record TransportRequest(String method, String path, String accessToken, Map<String, String> headers,
		String contentType, byte[] body, Duration timeout) {

	private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH");

	public TransportRequest {
		if (method == null || method.isBlank()) {
			throw new IllegalArgumentException("method is required");
		}
		method = method.toUpperCase(Locale.ROOT);
		if (!METHODS.contains(method)) {
			throw new IllegalArgumentException("Unsupported HTTP method: " + method);
		}
		if (path == null || !path.startsWith("/")) {
			throw new IllegalArgumentException("path must start with '/'");
		}
		Map<String, String> copy = new LinkedHashMap<>();
		if (headers != null) {
			headers.forEach((name, value) -> {
				if (name == null || name.isBlank()) {
					throw new IllegalArgumentException("header name is required");
				}
				if ("authorization".equalsIgnoreCase(name)) {
					throw new IllegalArgumentException("Set the token with accessToken, not an Authorization header");
				}
				if ("content-type".equalsIgnoreCase(name)) {
					throw new IllegalArgumentException("Set the type with contentType, not a Content-Type header");
				}
				copy.put(name, value == null ? "" : value);
			});
		}
		headers = Collections.unmodifiableMap(copy);
		if (body != null) {
			if (contentType == null || contentType.isBlank()) {
				throw new IllegalArgumentException("contentType is required with a body");
			}
			body = body.clone();
		}
		if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
			throw new IllegalArgumentException("timeout must be positive");
		}
	}

	/** Requête sans corps. */
	public static TransportRequest of(String method, String path, String accessToken) {
		return new TransportRequest(method, path, accessToken, Map.of(), null, null, null);
	}

	/** Ajoute (ou remplace) un en-tête. */
	public TransportRequest withHeader(String name, String value) {
		Map<String, String> all = new LinkedHashMap<>(headers);
		all.put(name, value);
		return new TransportRequest(method, path, accessToken, all, contentType, body, timeout);
	}

	/**
	 * Corps JSON (UTF-8), {@code application/json}. Un texte nul donne un corps
	 * vide.
	 */
	public TransportRequest withJsonBody(String json) {
		return withBody("application/json",
				(json == null ? "" : json).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	/** Corps binaire de type {@code contentType} (ex. {@code image/png}). */
	public TransportRequest withBody(String type, byte[] bytes) {
		return new TransportRequest(method, path, accessToken, headers, type, bytes, timeout);
	}

	/** Délai maximal de cette requête, à la place de celui du transport. */
	public TransportRequest withTimeout(Duration newTimeout) {
		return new TransportRequest(method, path, accessToken, headers, contentType, body, newTimeout);
	}

	/**
	 * Copie défensive : le tableau reçu ne peut pas être modifié par l'appelant.
	 */
	@Override
	public byte[] body() {
		return body == null ? null : body.clone();
	}

	/**
	 * Ne montre ni le jeton ni le corps : cette valeur finit facilement dans un
	 * journal.
	 */
	@Override
	public String toString() {
		return "TransportRequest[" + method + " " + path + ", token="
				+ (accessToken == null || accessToken.isBlank() ? "none" : "***") + ", headers=" + headers.keySet()
				+ ", contentType=" + contentType + ", body=" + (body == null ? "none" : body.length + " bytes")
				+ ", timeout=" + timeout + "]";
	}
}
