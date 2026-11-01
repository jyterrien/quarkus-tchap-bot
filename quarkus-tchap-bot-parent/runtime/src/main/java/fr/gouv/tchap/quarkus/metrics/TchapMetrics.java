package fr.gouv.tchap.quarkus.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class TchapMetrics {

    private final Counter messagesReceived;
    private final Counter messagesSent;
    private final Counter syncErrors;

    @Inject
    public TchapMetrics(MeterRegistry registry) {
        this.messagesReceived = registry.counter("tchap.messages.received");
        this.messagesSent     = registry.counter("tchap.messages.sent");
        this.syncErrors       = registry.counter("tchap.sync.errors");
    }

    public void incReceived() { messagesReceived.increment(); }
    public void incSent()     { messagesSent.increment(); }
    public void incSyncError(){ syncErrors.increment(); }
}
