package fr.gouv.tchap.matrix.exception;

/**
 * Racine de toutes les erreurs du SDK : un seul {@code catch (MatrixException)}
 * les attrape toutes, le 429 compris.
 *
 * <pre>
 * MatrixException
 * ├── AuthenticationException     identifiants refusés ou impossibles à renouveler : inutile de réessayer
 * ├── MatrixTransportException    la requête n'a pas abouti (réseau, connexion fermée) : réessayable
 * └── MatrixServerException       le serveur a répondu par une erreur HTTP (4xx / 5xx)
 *     └── RateLimitException      429 : trop de requêtes, à réessayer après un délai
 * </pre>
 *
 * Pour décider s'il faut réessayer : {@link MatrixTransportException}, 5xx et
 * 429 le méritent ; un 4xx autre que 429
 * ({@link MatrixServerException#isClientError()}) et une
 * {@link AuthenticationException} non.
 */
public class MatrixException extends RuntimeException {

	public MatrixException(String message) {
		super(message);
	}

	public MatrixException(String message, Throwable cause) {
		super(message, cause);
	}
}
