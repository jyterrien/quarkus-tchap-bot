package fr.gouv.tchap.matrix.transport.http;

import java.time.Duration;
import java.util.Objects;

/**
 * Délais du transport HTTP.
 *
 * @param connectTimeout
 *            délai maximal d'établissement de la connexion TCP
 * @param requestTimeout
 *            délai maximal d'une requête, sans aucune donnée reçue du serveur ;
 *            {@link Duration#ZERO} le désactive. <b>Doit dépasser la durée de
 *            maintien du long polling de {@code /sync}</b> (30 s par défaut)
 *            avec de la marge, sinon la synchronisation échoue à chaque tour.
 *            Une requête précise peut le remplacer
 *            ({@code TransportRequest#withTimeout}).
 *            <p>
 *            Aucun délai d'inactivité de connexion n'est posé exprès : il
 *            couperait aussi le long polling.
 */
public record HttpTransportOptions(Duration connectTimeout, Duration requestTimeout) {

	public HttpTransportOptions {
		Objects.requireNonNull(connectTimeout, "connectTimeout is required");
		Objects.requireNonNull(requestTimeout, "requestTimeout is required");
		if (connectTimeout.isZero() || connectTimeout.isNegative()) {
			throw new IllegalArgumentException("connectTimeout must be positive");
		}
		if (requestTimeout.isNegative()) {
			throw new IllegalArgumentException("requestTimeout must not be negative");
		}
	}

	/** 30 s pour se connecter, 60 s par requête. */
	public static HttpTransportOptions defaults() {
		return new HttpTransportOptions(Duration.ofSeconds(30), Duration.ofSeconds(60));
	}

	public HttpTransportOptions withRequestTimeout(Duration newRequestTimeout) {
		return new HttpTransportOptions(connectTimeout, newRequestTimeout);
	}

	public HttpTransportOptions withConnectTimeout(Duration newConnectTimeout) {
		return new HttpTransportOptions(newConnectTimeout, requestTimeout);
	}
}
