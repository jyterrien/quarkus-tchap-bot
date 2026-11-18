package fr.gouv.tchap.matrix.exception;

/**
 * Le serveur limite le débit (HTTP 429, {@code M_LIMIT_EXCEEDED}). C'est une
 * {@link MatrixServerException} : {@code statusCode()} vaut 429. Contrairement
 * aux autres 4xx, ce n'est pas un refus définitif ({@code isClientError()} est
 * faux) : la même requête réussira après le délai indiqué.
 */
public class RateLimitException extends MatrixServerException {

	private final long retryAfterMillis;

	/**
	 * @param retryAfterMillis
	 *            délai conseillé avant de réessayer, en millisecondes
	 */
	public RateLimitException(String message, long retryAfterMillis) {
		super(message, 429, "M_LIMIT_EXCEEDED", false);
		this.retryAfterMillis = retryAfterMillis;
	}

	public long retryAfterMillis() {
		return retryAfterMillis;
	}
}
