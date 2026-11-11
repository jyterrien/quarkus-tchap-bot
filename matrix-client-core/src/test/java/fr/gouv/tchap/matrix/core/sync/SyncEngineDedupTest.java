package fr.gouv.tchap.matrix.core.sync;

import static fr.gouv.tchap.matrix.core.HttpTransportMocks.awaitGets;
import static fr.gouv.tchap.matrix.core.HttpTransportMocks.scriptedGet;
import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.event.MatrixEvent;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class SyncEngineDedupTest {

	/**
	 * Réponse /sync dont la timeline du salon !r:s contient les event_id donnés.
	 */
	private static String response(int batch, String... ids) {
		StringBuilder events = new StringBuilder();
		for (String id : ids) {
			if (events.length() > 0)
				events.append(',');
			events.append("{\"event_id\":\"").append(id).append("\",")
					.append("\"sender\":\"@u:s\",\"type\":\"m.room.message\",")
					.append("\"origin_server_ts\":1,\"content\":{\"body\":\"x\"}}");
		}
		return "{\"next_batch\":\"s" + batch + "\",\"rooms\":{\"join\":{\"!r:s\":" + "{\"timeline\":{\"events\":["
				+ events + "]}}}}}";
	}

	/** Une réponse /sync par élément du script (le dernier se répète). */
	private static HttpTransport transport(String[]... script) {
		Object[] steps = new Object[script.length];
		for (int i = 0; i < script.length; i++) {
			steps[i] = response(i + 1, script[i]);
		}
		return scriptedGet(steps);
	}

	private void run(HttpTransport transport, int callsToWait, int dedupCapacity, Consumer<MatrixEvent> consumer) {
		SyncEngine engine = new SyncEngine(new MatrixHttpApi(transport, "tok"), new InMemorySyncTokenStore(), consumer,
				0, dedupCapacity);
		engine.start().toCompletableFuture().join();
		try {
			awaitGets(transport, callsToWait);
		} finally {
			engine.stop().toCompletableFuture().join();
		}
	}

	@Test
	void sameEventInSeveralSyncs_isDeliveredOnce() {
		List<String> delivered = new CopyOnWriteArrayList<>();

		run(transport(new String[]{"$e1"}), 3, 100, ev -> delivered.add(ev.eventId()));

		assertEquals(List.of("$e1"), delivered);
	}

	@Test
	void dedupWindowIsBounded_oldestIdsAreForgotten() {
		List<String> delivered = new CopyOnWriteArrayList<>();
		// capacité 2 : après e1, e2, e3 → e1 est évincé et sera redélivré
		HttpTransport transport = transport(new String[]{"$e1"}, new String[]{"$e2"}, new String[]{"$e3"},
				new String[]{"$e1"});

		run(transport, 5, 2, ev -> delivered.add(ev.eventId()));

		assertEquals(List.of("$e1", "$e2", "$e3", "$e1"), delivered);
	}

	@Test
	void consumerException_doesNotStopTheLoopNorOtherEvents() {
		List<String> delivered = new CopyOnWriteArrayList<>();
		HttpTransport transport = transport(new String[]{"$e1", "$e2"}, new String[]{"$e3"});

		run(transport, 3, 100, ev -> {
			if (ev.eventId().equals("$e1"))
				throw new IllegalStateException("boom");
			delivered.add(ev.eventId());
		});

		assertEquals(List.of("$e2", "$e3"), delivered);
	}

	@Test
	void invalidCapacity_isRejected() {
		assertThrows(IllegalArgumentException.class, () -> new SyncEngine(null, new InMemorySyncTokenStore(), ev -> {
		}, 0, 0));
	}
}
