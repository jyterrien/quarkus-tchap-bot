package fr.gouv.tchap.matrix.exception;

/**
 * Erreur renvoyée par le homeserver (statut HTTP 4xx/5xx, hors 429 qui est une
 * {@link RateLimitException}). Le message est « HTTP &lt;statut&gt;:
 * &lt;corps&gt; » ; le code d'erreur Matrix est exposé à part.
 */
public class MatrixServerException extends MatrixException {

	private final int statusCode;
	private final String errcode;
	private final boolean softLogout;

	/**
	 * @param errcode
	 *            code d'erreur Matrix (ex. {@code M_UNKNOWN_TOKEN}), ou
	 *            {@code null} si le corps n'en contient pas
	 * @param softLogout
	 *            vrai si le serveur indique que la session peut être rétablie
	 *            (token expiré, pas révoqué)
	 */
	public MatrixServerException(String message, int statusCode, String errcode, boolean softLogout) {
		super(message);
		this.statusCode = statusCode;
		this.errcode = errcode;
		this.softLogout = softLogout;
	}

	public int statusCode() {
		return statusCode;
	}

	public String errcode() {
		return errcode;
	}

	public boolean softLogout() {
		return softLogout;
	}

	/**
	 * Vrai si le serveur a refusé le token d'accès (expiré, révoqué ou inconnu).
	 */
	public boolean isTokenRejected() {
		return statusCode == 401 || "M_UNKNOWN_TOKEN".equals(errcode);
	}

	/**
	 * Vrai pour un refus définitif de la requête (4xx) : la rejouer à l'identique
	 * ne changera rien.
	 */
	public boolean isClientError() {
		return statusCode >= 400 && statusCode < 500;
	}
}
