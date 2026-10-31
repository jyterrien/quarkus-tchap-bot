package fr.gouv.tchap.matrix.core.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.event.MatrixEvent;
import fr.gouv.tchap.matrix.sync.SyncTokenStore;

import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class SyncEngine {

    private final MatrixHttpApi api;
    private final SyncTokenStore tokenStore;
    private final Consumer<MatrixEvent> eventConsumer;
    private final SyncEventParser parser;
    private final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService executor;
    private volatile String currentToken;
    private final int initialLimit;

    public SyncEngine(MatrixHttpApi api, SyncTokenStore tokenStore,
                    Consumer<MatrixEvent> eventConsumer) {
        this(api, tokenStore, eventConsumer, 0);
    }

    public SyncEngine(MatrixHttpApi api, SyncTokenStore tokenStore,
                    Consumer<MatrixEvent> eventConsumer, int initialLimit) {
        this.api = api;
        this.tokenStore = tokenStore;
        this.eventConsumer = eventConsumer;
        this.initialLimit = initialLimit;
        this.parser = new SyncEventParser(new ObjectMapper());
    }

    public CompletionStage<Void> start() {
        if (!running.compareAndSet(false, true)) return CompletableFuture.completedFuture(null);
        return tokenStore.load().thenCompose(opt -> {
            currentToken = opt.orElse(null);
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tchap-sync");
                t.setDaemon(true);
                return t;
            });
            schedule(0);
            return CompletableFuture.completedFuture(null);
        });
    }

    public CompletionStage<Void> stop() {
        running.set(false);
        if (executor != null) executor.shutdownNow();
        return CompletableFuture.completedFuture(null);
    }

    private void schedule(long delayMs) {
        if (running.get()) executor.schedule(this::doSync, delayMs, TimeUnit.MILLISECONDS);
    }

    private void doSync() {
        if (!running.get()) return;
        System.out.println("SYNC");
        api.sync(currentToken, 30_000, initialLimit)
            .thenAccept(response -> {
                System.out.println(String.format("SYNC: %s", response.substring(0, Math.min(2000, response.length()))));
                 String nextBatch = parser.extractNextBatch(response);
                if (nextBatch != null && !nextBatch.isBlank()) {
                    List<MatrixEvent> events = parser.parseEvents(response);
                    for (MatrixEvent ev : events) {
                        // at-least-once + dédup
                        if (processedEventIds.add(ev.eventId())) {
                            try {
                                eventConsumer.accept(ev);
                            } catch (Exception e) {
                                // On log côté runtime ; ici on isole pour ne pas casser la boucle
                            }
                        }
                    }
                    currentToken = nextBatch;
                    tokenStore.save(nextBatch);
                }
                schedule(0);
            })
            .exceptionally(ex -> {
                ex.printStackTrace();
                schedule(5_000);
                return null;
            });
    }

    public boolean isRunning() { return running.get(); }
}
