package fr.gouv.tchap.matrix.exception;

public class RateLimitException extends MatrixTransportException {
    private final long retryAfterMillis;

    public RateLimitException(String message, long retryAfterMillis) {
        super(message);
        this.retryAfterMillis = retryAfterMillis;
    }

    public long retryAfterMillis() { return retryAfterMillis; }
}