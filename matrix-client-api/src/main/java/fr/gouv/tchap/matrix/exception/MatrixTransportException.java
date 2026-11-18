package fr.gouv.tchap.matrix.exception;

/**
 * La requête n'a pas abouti : serveur injoignable, connexion refusée ou coupée,
 * transport fermé. Le serveur n'a rien répondu (sinon c'est une
 * {@link MatrixServerException}). Une erreur de ce type est transitoire : la
 * requête peut être rejouée.
 */
public class MatrixTransportException extends MatrixException {

	public MatrixTransportException(String message) {
		super(message);
	}

	public MatrixTransportException(String message, Throwable cause) {
		super(message, cause);
	}
}
