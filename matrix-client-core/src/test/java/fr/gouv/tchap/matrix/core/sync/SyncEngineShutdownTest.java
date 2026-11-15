package fr.gouv.tchap.matrix.core.sync;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.event.MatrixEvent;
import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Arrêt (et redémarrage) pendant un long polling en cours : la requête
 * abandonnée, qui échoue quand le transport se ferme ou répond après coup, ne
 * doit plus rien modifier.
 */
class SyncEngineShutdownTest {

	private static final String RESPONSE_WITH_EVENT = "{\"next_batch\":\"s1\",\"rooms\":{\"join\":{\"!r:s\":{\"timeline\":{"
			+ "\"events\":[{\"event_id\":\"$e1\",\"sender\":\"@alice:s\",\"type\":\"m.room.message\","
			+ "\"origin_server_ts\":1,\"content\":{\"msgtype\":\"m.text\",\"body\":\"x\"}}]}}}}}";

	/** Chaque GET /sync reste en attente jusqu'à ce que le test le termine. */
	private final List<CompletableFuture<String>> pending = new CopyOnWriteArrayList<>();
	private final List<MatrixEvent> delivered = new CopyOnWriteArrayList<>();
	private final InMemorySyncTokenStore tokenStore = new InMemorySyncTokenStore();

	private SyncEngine engine() {
		HttpTransport transport = mock(HttpTransport.class);
		doAnswer(inv -> {
			CompletableFuture<String> request = new CompletableFuture<>();
			pending.add(request);
			return request;
		}).when(transport).get(any(), any());
		return new SyncEngine(new MatrixHttpApi(transport, "tok"), tokenStore, delivered::add);
	}

	private static void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 3_000;
		while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
		assertTrue(condition.getAsBoolean(), "condition non atteinte à temps");
	}

	@Test
	void requestFailingBecauseOfTheShutdown_isNotCountedAsAFailure() throws Exception {
		SyncEngine engine = engine();
		engine.start().toCompletableFuture().join();
		await(() -> pending.size() == 1);

		engine.stop().toCompletableFuture().join();
		// Le transport se ferme : le long polling en cours est coupé
		pending.get(0).completeExceptionally(new MatrixException("Vert.x transport error: Connection was closed"));

		assertEquals(0, engine.health().consecutiveFailures());
		assertNull(engine.health().lastError());
		Thread.sleep(200);
		assertEquals(1, pending.size(), "aucune relance après l'arrêt");
	}

	@Test
	void responseArrivingAfterTheStop_isNeitherDeliveredNorSaved() throws Exception {
		SyncEngine engine = engine();
		engine.start().toCompletableFuture().join();
		await(() -> pending.size() == 1);

		engine.stop().toCompletableFuture().join();
		pending.get(0).complete(RESPONSE_WITH_EVENT);

		assertTrue(delivered.isEmpty(), "aucun événement ne doit être livré après l'arrêt");
		assertTrue(tokenStore.load().toCompletableFuture().join().isEmpty(), "le token ne doit pas être sauvegardé");
		Thread.sleep(200);
		assertEquals(1, pending.size());
	}

	@Test
	void restart_ignoresTheRequestOfThePreviousLoop() throws Exception {
		SyncEngine engine = engine();
		engine.start().toCompletableFuture().join();
		await(() -> pending.size() == 1);
		engine.stop().toCompletableFuture().join();
		engine.start().toCompletableFuture().join();
		await(() -> pending.size() == 2);

		// L'ancienne requête aboutit enfin : elle ne doit pas lancer une seconde boucle
		pending.get(0).complete("{\"next_batch\":\"old\"}");
		Thread.sleep(300);
		assertEquals(2, pending.size(), "une seule boucle doit tourner");
		assertTrue(delivered.isEmpty());

		// La boucle active, elle, continue normalement
		pending.get(1).complete("{\"next_batch\":\"new\"}");
		await(() -> pending.size() == 3);
		engine.stop().toCompletableFuture().join();
	}

	@Test
	void aRequestFailingWhileRunning_isStillCountedAndRetried() throws Exception {
		SyncEngine engine = new SyncEngine(new MatrixHttpApi(failingTransport(), "tok"), tokenStore, delivered::add) {
			@Override
			void schedule(long delayMs) {
				super.schedule(0);
			}
		};
		engine.start().toCompletableFuture().join();
		try {
			await(() -> engine.health().consecutiveFailures() >= 1);
			assertNotNull(engine.health().lastError());
		} finally {
			engine.stop().toCompletableFuture().join();
		}
	}

	private HttpTransport failingTransport() {
		HttpTransport transport = mock(HttpTransport.class);
		doAnswer(inv -> CompletableFuture.<String>failedFuture(new MatrixException("HTTP 502: bad gateway")))
				.when(transport).get(any(), any());
		return transport;
	}
}
