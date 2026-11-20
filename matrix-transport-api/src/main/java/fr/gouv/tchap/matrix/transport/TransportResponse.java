package fr.gouv.tchap.matrix.transport;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Réponse HTTP réussie (statut inférieur à 400) renvoyée par
 * {@link HttpTransport#execute}, avec son corps en octets : indispensable pour
 * les médias, que {@code send} ne sait pas rendre (il lit le corps comme du
 * texte). Les erreurs (4xx, 5xx) ne sont pas des réponses : elles échouent en
 * {@code MatrixServerException}.
 *
 * @param statusCode
 *            statut HTTP
 * @param headers
 *            en-têtes de la réponse, noms en minuscules, première valeur de
 *            chacun
 * @param body
 *            corps, jamais nul (vide s'il n'y en a pas)
 */
public record TransportResponse(int statusCode, Map<String, String> headers, byte[] body) {

	public TransportResponse {
		Map<String, String> lower = new LinkedHashMap<>();
		if (headers != null) {
			headers.forEach((name, value) -> {
				if (name != null) {
					lower.putIfAbsent(name.toLowerCase(Locale.ROOT), value == null ? "" : value);
				}
			});
		}
		headers = Collections.unmodifiableMap(lower);
		body = body == null ? new byte[0] : body.clone();
	}

	/**
	 * Valeur d'un en-tête, sans tenir compte de la casse ; {@code null} s'il est
	 * absent.
	 */
	public String header(String name) {
		return name == null ? null : headers.get(name.toLowerCase(Locale.ROOT));
	}

	/** Le corps lu comme du texte UTF-8. */
	public String bodyAsString() {
		return new String(body, StandardCharsets.UTF_8);
	}

	/** Copie défensive. */
	@Override
	public byte[] body() {
		return body.clone();
	}

	/** Ne décrit que la taille du corps : il peut être volumineux ou sensible. */
	@Override
	public String toString() {
		return "TransportResponse[" + statusCode + ", headers=" + headers.keySet() + ", body=" + body.length
				+ " bytes]";
	}
}
