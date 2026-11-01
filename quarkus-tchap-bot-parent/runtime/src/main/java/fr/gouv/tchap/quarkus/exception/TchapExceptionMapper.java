package fr.gouv.tchap.quarkus.exception;

import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.exception.RateLimitException;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class TchapExceptionMapper {

    private static final Logger LOG = Logger.getLogger(TchapExceptionMapper.class);

    public void handle(Throwable t) {
        if (t instanceof RateLimitException rl) {
            LOG.warnf("Rate limited, retry after %d ms", rl.retryAfterMillis());
        } else if (t instanceof MatrixException me) {
            LOG.errorf("Matrix error: %s", me.getMessage());
        } else {
            LOG.error("Unexpected error", t);
        }
    }
}
