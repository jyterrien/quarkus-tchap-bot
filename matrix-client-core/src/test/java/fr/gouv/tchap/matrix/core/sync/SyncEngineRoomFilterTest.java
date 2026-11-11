package fr.gouv.tchap.matrix.core.sync;

import static fr.gouv.tchap.matrix.core.HttpTransportMocks.awaitGets;
import static fr.gouv.tchap.matrix.core.HttpTransportMocks.getPaths;
import static fr.gouv.tchap.matrix.core.HttpTransportMocks.scriptedGet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.event.MatrixEvent;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class SyncEngineRoomFilterTest {

	private static String joined(String roomId, String... eventIds) {
		StringBuilder events = new StringBuilder();
		for (String id : eventIds) {
			if (events.length() > 0)
				events.append(',');
			events.append("{\"event_id\":\"").append(id).append("\",\"sender\":\"@u:s\",")
					.append("\"type\":\"m.room.message\",\"origin_server_ts\":1,\"content\":{}}");
		}
		return "\"" + roomId + "\":{\"timeline\":{\"events\":[" + events + "]}}";
	}

	private static String response(String nextBatch, String joinedRooms, String invitedRooms) {
		return "{\"next_batch\":\"" + nextBatch + "\",\"rooms\":{\"join\":{" + joinedRooms + "}," + "\"invite\":{"
				+ invitedRooms + "}}}";
	}

	private static String decoded(String path) {
		return URLDecoder.decode(path, StandardCharsets.UTF_8);
	}

	private static void runUntilGets(SyncEngine engine, HttpTransport transport, int calls) {
		engine.start().toCompletableFuture().join();
		try {
			awaitGets(transport, calls);
		} finally {
			engine.stop().toCompletableFuture().join();
		}
	}

	private static SyncEngine engine(HttpTransport transport, Consumer<MatrixEvent> events, Consumer<String> invites) {
		return new SyncEngine(new MatrixHttpApi(transport, "tok"), new InMemorySyncTokenStore(), events, invites, 0,
				30_000);
	}

	@Test
	void roomFilter_isSentOnEveryRequest_historyLimitOnlyOnTheFirst() {
		HttpTransport transport = scriptedGet(response("s1", "", ""));
		SyncEngine engine = engine(transport, ev -> {
		}, null);
		engine.setRoomFilter(List.of("!a:s"));

		runUntilGets(engine, transport, 2);

		List<String> paths = getPaths(transport);
		assertTrue(decoded(paths.get(0)).contains("{\"room\":{\"rooms\":[\"!a:s\"],\"timeline\":{\"limit\":0}}}"),
				decoded(paths.get(0)));
		assertTrue(decoded(paths.get(1)).contains("since=s1"));
		assertTrue(decoded(paths.get(1)).contains("{\"room\":{\"rooms\":[\"!a:s\"]}}"), decoded(paths.get(1)));
	}

	@Test
	void eventsFromOtherRooms_areDroppedEvenIfTheServerSendsThem() {
		String both = joined("!a:s", "$e1") + "," + joined("!b:s", "$e2");
		HttpTransport transport = scriptedGet(response("s1", both, ""));
		List<String> delivered = new CopyOnWriteArrayList<>();
		SyncEngine engine = engine(transport, ev -> delivered.add(ev.eventId()), null);
		engine.setRoomFilter(Set.of("!a:s"));

		runUntilGets(engine, transport, 2);

		assertEquals(List.of("$e1"), delivered);
	}

	@Test
	void withoutFilter_allRoomsAreDelivered() {
		String both = joined("!a:s", "$e1") + "," + joined("!b:s", "$e2");
		HttpTransport transport = scriptedGet(response("s1", both, ""));
		List<String> delivered = new CopyOnWriteArrayList<>();

		runUntilGets(engine(transport, ev -> delivered.add(ev.eventId()), null), transport, 2);

		assertEquals(List.of("$e1", "$e2"), delivered);
	}

	@Test
	void invitationsToOtherRooms_areIgnored() {
		String invites = "\"!a:s\":{},\"!other:s\":{}";
		// L'invitation n'apparaît que dans la première réponse : une réponse répétée la
		// rejouerait à chaque /sync
		HttpTransport transport = scriptedGet(response("s1", "", invites), response("s2", "", ""));
		List<String> invited = new CopyOnWriteArrayList<>();
		SyncEngine engine = engine(transport, ev -> {
		}, invited::add);
		engine.setRoomFilter(Set.of("!a:s"));

		runUntilGets(engine, transport, 2);

		assertEquals(List.of("!a:s"), invited);
	}

	@Test
	void roomFilter_canBeChangedWhileRunning_effectiveOnNextSync() {
		AtomicReference<SyncEngine> ref = new AtomicReference<>();
		AtomicInteger calls = new AtomicInteger();
		HttpTransport transport = mock(HttpTransport.class);
		doAnswer(invocation -> {
			if (calls.getAndIncrement() == 0) {
				ref.get().setRoomFilter(Set.of("!b:s"));
			}
			return CompletableFuture.completedFuture(response("s1", "", ""));
		}).when(transport).get(any(), any());
		SyncEngine engine = engine(transport, ev -> {
		}, null);
		ref.set(engine);
		engine.setRoomFilter(Set.of("!a:s"));

		runUntilGets(engine, transport, 2);

		String second = decoded(getPaths(transport).get(1));
		assertTrue(second.contains("[\"!b:s\"]"), second);
		assertFalse(second.contains("!a:s"));
	}
}
