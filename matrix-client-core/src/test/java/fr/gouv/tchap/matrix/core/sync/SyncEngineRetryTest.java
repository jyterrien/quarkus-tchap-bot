package fr.gouv.tchap.matrix.core.sync;

import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.exception.RateLimitException;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SyncEngineRetryTest {

    @Test
    void rateLimit_usesServerDelay() {
        assertEquals(2_500, SyncEngine.retryDelayMs(new RateLimitException("HTTP 429", 2_500)));
    }

    @Test
    void rateLimit_delayIsBoundedBetweenOneSecondAndFiveMinutes() {
        assertEquals(1_000, SyncEngine.retryDelayMs(new RateLimitException("HTTP 429", 0)));
        assertEquals(1_000, SyncEngine.retryDelayMs(new RateLimitException("HTTP 429", 200)));
        assertEquals(300_000, SyncEngine.retryDelayMs(new RateLimitException("HTTP 429", 10_000_000)));
    }

    @Test
    void otherErrors_useTheFixedFiveSecondDelay() {
        assertEquals(5_000, SyncEngine.retryDelayMs(new MatrixException("HTTP 500")));
        assertEquals(5_000, SyncEngine.retryDelayMs(new RuntimeException("boom")));
    }

    @Test
    void afterARateLimit_engineRetriesAfterTheServerDelay_notTheDefaultFiveSeconds() throws Exception {
        List<Long> callTimes = new CopyOnWriteArrayList<>();
        CountDownLatch twoCalls = new CountDownLatch(2);

        HttpTransport transport = new HttpTransport() {
            @Override
            public CompletionStage<String> get(String path, String accessToken) {
                callTimes.add(System.nanoTime());
                twoCalls.countDown();
                return callTimes.size() == 1
                    ? CompletableFuture.failedFuture(new RateLimitException("HTTP 429", 1_000))
                    : CompletableFuture.completedFuture("{\"next_batch\":\"s1\"}");
            }

            @Override
            public CompletionStage<String> put(String p, String t, String b) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CompletionStage<String> post(String p, String t, String b) {
                throw new UnsupportedOperationException();
            }
        };

        SyncEngine engine = new SyncEngine(new MatrixHttpApi(transport, "tok"),
            new InMemorySyncTokenStore(), ev -> {});
        engine.start().toCompletableFuture().join();
        try {
            assertTrue(twoCalls.await(10, TimeUnit.SECONDS), "deux /sync attendus");
        } finally {
            engine.stop().toCompletableFuture().join();
        }

        long gapMs = (callTimes.get(1) - callTimes.get(0)) / 1_000_000;
        assertTrue(gapMs >= 900, "le délai du serveur (1 s) doit être respecté, écart = " + gapMs + " ms");
        assertTrue(gapMs < 3_500, "ce ne doit pas être le délai par défaut de 5 s, écart = " + gapMs + " ms");
    }
}
