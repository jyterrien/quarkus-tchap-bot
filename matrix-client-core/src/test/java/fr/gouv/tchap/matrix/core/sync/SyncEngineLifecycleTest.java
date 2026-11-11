package fr.gouv.tchap.matrix.core.sync;

import static fr.gouv.tchap.matrix.core.HttpTransportMocks.awaitGets;
import static fr.gouv.tchap.matrix.core.HttpTransportMocks.getPaths;
import static fr.gouv.tchap.matrix.core.HttpTransportMocks.scriptedGet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Démarrage, arrêt et redémarrage du moteur. */
class SyncEngineLifecycleTest {

	@Test
	void startingTwice_startsASingleLoop() {
		AtomicInteger schedules = new AtomicInteger();
		SyncEngine engine = new SyncEngine(new MatrixHttpApi(mock(HttpTransport.class), "tok"),
				new InMemorySyncTokenStore(), ev -> {
				}) {
			@Override
			void schedule(long delayMs) {
				schedules.incrementAndGet(); // la boucle ne tourne pas : on compte seulement les démarrages
			}
		};

		engine.start().toCompletableFuture().join();
		engine.start().toCompletableFuture().join();
		engine.stop().toCompletableFuture().join();

		assertEquals(1, schedules.get());
	}

	@Test
	void afterStop_theEngineCanBeStartedAgain() {
		HttpTransport transport = scriptedGet("{\"next_batch\":\"s1\"}");
		SyncEngine engine = new SyncEngine(new MatrixHttpApi(transport, "tok"), new InMemorySyncTokenStore(), ev -> {
		});
		engine.start().toCompletableFuture().join();
		awaitGets(transport, 2);
		engine.stop().toCompletableFuture().join();
		int beforeRestart = getPaths(transport).size();

		engine.start().toCompletableFuture().join();
		try {
			// marge de 3 : une requête déjà en vol à l'arrêt a pu s'ajouter à beforeRestart
			awaitGets(transport, beforeRestart + 3);
		} finally {
			engine.stop().toCompletableFuture().join();
		}
	}

	@Test
	void stopBeforeStart_isHarmless() {
		SyncEngine engine = new SyncEngine(new MatrixHttpApi(mock(HttpTransport.class), "tok"),
				new InMemorySyncTokenStore(), ev -> {
				});

		assertDoesNotThrow(() -> engine.stop().toCompletableFuture().join());
	}
}
