package fr.gouv.tchap.matrix.exception;

public class AuthenticationException extends MatrixException {

	public AuthenticationException(String message) {
		super(message);
	}

	public AuthenticationException(String message, Throwable cause) {
		super(message, cause);
	}
}
