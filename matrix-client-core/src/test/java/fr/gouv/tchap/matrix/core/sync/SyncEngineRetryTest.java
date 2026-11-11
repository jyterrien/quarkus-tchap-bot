package fr.gouv.tchap.matrix.core.sync;

import static fr.gouv.tchap.matrix.core.HttpTransportMocks.awaitGets;
import static fr.gouv.tchap.matrix.core.HttpTransportMocks.scriptedGet;
import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.exception.RateLimitException;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

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

	/**
	 * Délais demandés au planificateur par le moteur : le premier est le démarrage
	 * (0), le second est le délai de retry choisi après l'échec. Le délai n'est pas
	 * attendu : on relance aussitôt.
	 */
	private static List<Long> schedulesAfter(Throwable failure) {
		List<Long> delays = new CopyOnWriteArrayList<>();
		HttpTransport transport = scriptedGet(failure, "{\"next_batch\":\"s1\"}");
		SyncEngine engine = new SyncEngine(new MatrixHttpApi(transport, "tok"), new InMemorySyncTokenStore(), ev -> {
		}) {
			@Override
			void schedule(long delayMs) {
				delays.add(delayMs);
				super.schedule(0);
			}
		};
		engine.start().toCompletableFuture().join();
		try {
			awaitGets(transport, 2);
		} finally {
			engine.stop().toCompletableFuture().join();
		}
		return List.copyOf(delays).subList(0, 2);
	}

	@Test
	void afterARateLimit_engineSchedulesTheRetryWithTheServerDelay() {
		assertEquals(List.of(0L, 7_000L), schedulesAfter(new RateLimitException("HTTP 429", 7_000)));
	}

	@Test
	void afterAnotherError_engineSchedulesTheRetryWithTheFixedFiveSecondDelay() {
		assertEquals(List.of(0L, 5_000L), schedulesAfter(new MatrixException("HTTP 500")));
	}
}
