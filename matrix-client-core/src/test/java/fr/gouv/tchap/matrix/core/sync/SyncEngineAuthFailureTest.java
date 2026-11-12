package fr.gouv.tchap.matrix.core.sync;

import static fr.gouv.tchap.matrix.core.HttpTransportMocks.scriptedGet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.exception.AuthenticationException;
import fr.gouv.tchap.matrix.exception.MatrixServerException;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Identifiants refusés : la boucle s'arrête (au lieu de réessayer toutes les 5
 * s) et le signale.
 */
class SyncEngineAuthFailureTest {

	@Test
	void rejectedStaticToken_stopsTheLoop_andNotifiesTheFatalHandler() throws Exception {
		HttpTransport transport = scriptedGet(
				new MatrixServerException("HTTP 401: {\"errcode\":\"M_UNKNOWN_TOKEN\"}", 401, "M_UNKNOWN_TOKEN", true));
		SyncEngine engine = new SyncEngine(new MatrixHttpApi(transport, "expired"), new InMemorySyncTokenStore(),
				ev -> {
				}) {
			@Override
			void schedule(long delayMs) {
				super.schedule(0);
			}
		};
		CountDownLatch notified = new CountDownLatch(1);
		AtomicReference<Throwable> cause = new AtomicReference<>();
		engine.onFatalError(t -> {
			cause.set(t);
			notified.countDown();
		});

		engine.start().toCompletableFuture().join();

		assertTrue(notified.await(5, TimeUnit.SECONDS), "le handler d'erreur définitive doit être appelé");
		assertInstanceOf(AuthenticationException.class, cause.get());
		Thread.sleep(300);
		verify(transport, times(1)).get(any(), any()); // aucune relance
		assertEquals(1, engine.health().consecutiveFailures());
		assertNotNull(engine.health().lastError());
	}
}
