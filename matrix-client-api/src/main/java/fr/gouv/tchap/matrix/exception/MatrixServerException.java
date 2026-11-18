package fr.gouv.tchap.matrix.exception;

/**
 * Erreur renvoyée par le homeserver (statut HTTP 4xx/5xx). Le 429 en est un cas
 * particulier : {@link RateLimitException}, sous-classe de celle-ci. Le message
 * est « HTTP &lt;statut&gt;: &lt;corps&gt; » ; le code d'erreur Matrix est
 * exposé à part.
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
	 * Refus définitif de la requête : 4xx autre que 429. La rejouer à l'identique
	 * ne changera rien. Le 429 (voir {@link #isRateLimited()}) n'en est pas un : il
	 * réussira après un délai.
	 */
	public boolean isClientError() {
		return statusCode >= 400 && statusCode < 500 && statusCode != 429;
	}

	/** Le serveur limite le débit (429) : réessayer après un délai. */
	public boolean isRateLimited() {
		return statusCode == 429;
	}

	/**
	 * Erreur du serveur (5xx) : en général transitoire, la requête peut être
	 * rejouée.
	 */
	public boolean isServerError() {
		return statusCode >= 500 && statusCode < 600;
	}
}
