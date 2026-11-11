package fr.gouv.tchap.matrix.core.sync;

import static fr.gouv.tchap.matrix.core.HttpTransportMocks.awaitGets;
import static fr.gouv.tchap.matrix.core.HttpTransportMocks.scriptedGet;
import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.sync.SyncHealth;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import org.junit.jupiter.api.Test;

class SyncEngineHealthTest {

	private static SyncEngine engine(HttpTransport transport) {
		// Les 5 s de délai de retry ne sont pas attendues : on relance aussitôt
		return new SyncEngine(new MatrixHttpApi(transport, "tok"), new InMemorySyncTokenStore(), ev -> {
		}) {
			@Override
			void schedule(long delayMs) {
				super.schedule(0);
			}
		};
	}

	@Test
	void beforeStart_healthIsNotStarted() {
		SyncEngine engine = engine(scriptedGet("{\"next_batch\":\"s1\"}"));

		SyncHealth h = engine.health();

		assertFalse(h.isStarted());
		assertNull(h.lastSuccess());
		assertEquals(0, h.consecutiveFailures());
	}

	@Test
	void afterASuccessfulSync_lastSuccessIsSetAndFailuresAreZero() {
		HttpTransport transport = scriptedGet("{\"next_batch\":\"s1\"}");
		SyncEngine engine = engine(transport);
		engine.start().toCompletableFuture().join();
		try {
			awaitGets(transport, 2);

			SyncHealth h = engine.health();

			assertTrue(h.isStarted());
			assertNotNull(h.lastSuccess());
			assertEquals(0, h.consecutiveFailures());
			assertNull(h.lastError());
		} finally {
			engine.stop().toCompletableFuture().join();
		}
	}

	@Test
	void whileEverySyncFails_failuresAreCountedAndTheErrorIsKept() {
		HttpTransport transport = scriptedGet(new MatrixException("HTTP 502"));
		SyncEngine engine = engine(transport);
		engine.start().toCompletableFuture().join();
		try {
			awaitGets(transport, 3);

			SyncHealth h = engine.health();

			assertNull(h.lastSuccess());
			assertTrue(h.consecutiveFailures() >= 1);
			assertNotNull(h.lastError());
			assertTrue(h.lastError().contains("HTTP 502"), h.lastError());
		} finally {
			engine.stop().toCompletableFuture().join();
		}
	}

	@Test
	void aSuccessAfterFailures_resetsTheCounterAndTheError() throws Exception {
		HttpTransport transport = scriptedGet(new MatrixException("HTTP 500"), new MatrixException("HTTP 500"),
				"{\"next_batch\":\"s1\"}");
		SyncEngine engine = engine(transport);
		engine.start().toCompletableFuture().join();
		try {
			awaitGets(transport, 4); // les deux échecs, puis au moins deux réussites

			SyncHealth h = engine.health();

			assertNotNull(h.lastSuccess());
			assertEquals(0, h.consecutiveFailures());
			assertNull(h.lastError());
		} finally {
			engine.stop().toCompletableFuture().join();
		}
	}
}
