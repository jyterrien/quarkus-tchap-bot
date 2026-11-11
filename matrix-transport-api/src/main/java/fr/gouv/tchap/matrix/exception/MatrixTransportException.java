package fr.gouv.tchap.matrix.exception;

public class MatrixTransportException extends RuntimeException {
	public MatrixTransportException(String message) {
		super(message);
	}

	public MatrixTransportException(String message, Throwable cause) {
		super(message, cause);
	}
}
