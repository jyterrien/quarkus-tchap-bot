package fr.gouv.tchap.matrix.transport.http;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.matrix.transport.HttpTransport;
import fr.gouv.tchap.matrix.transport.TransportRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TransportRequestTest {

	@Test
	void of_buildsABodylessRequest() {
		TransportRequest r = TransportRequest.of("DELETE", "/_matrix/x", "tok");

		assertEquals("DELETE", r.method());
		assertEquals("/_matrix/x", r.path());
		assertEquals("tok", r.accessToken());
		assertTrue(r.headers().isEmpty());
		assertNull(r.body());
		assertNull(r.contentType());
		assertNull(r.timeout());
	}

	@Test
	void method_isNormalizedToUpperCase_andRestrictedToTheMatrixOnes() {
		assertEquals("PUT", TransportRequest.of("put", "/x", null).method());
		assertThrows(IllegalArgumentException.class, () -> TransportRequest.of("FETCH", "/x", null));
		assertThrows(IllegalArgumentException.class, () -> TransportRequest.of(" ", "/x", null));
		assertThrows(IllegalArgumentException.class, () -> TransportRequest.of(null, "/x", null));
	}

	@Test
	void path_mustBeAbsolute() {
		assertThrows(IllegalArgumentException.class, () -> TransportRequest.of("GET", "x", null));
		assertThrows(IllegalArgumentException.class, () -> TransportRequest.of("GET", null, null));
		assertThrows(IllegalArgumentException.class, () -> TransportRequest.of("GET", "http://other/x", null));
	}

	@Test
	void authorizationAndContentTypeHeaders_areRefused_inAnyCase() {
		TransportRequest r = TransportRequest.of("GET", "/x", "tok");

		assertThrows(IllegalArgumentException.class, () -> r.withHeader("Authorization", "Bearer x"));
		assertThrows(IllegalArgumentException.class, () -> r.withHeader("authorization", "Bearer x"));
		assertThrows(IllegalArgumentException.class, () -> r.withHeader("Content-Type", "text/plain"));
		assertThrows(IllegalArgumentException.class, () -> r.withHeader("CONTENT-TYPE", "text/plain"));
		assertThrows(IllegalArgumentException.class, () -> r.withHeader(" ", "v"));
	}

	@Test
	void headers_areCopied_keepTheirOrder_andAreImmutable() {
		Map<String, String> source = new LinkedHashMap<>();
		source.put("X-Request-ID", "abc");
		source.put("User-Agent", "bot/1");

		TransportRequest r = new TransportRequest("GET", "/x", null, source, null, null, null);
		source.put("X-Other", "late");

		assertEquals(java.util.List.of("X-Request-ID", "User-Agent"), java.util.List.copyOf(r.headers().keySet()));
		assertThrows(UnsupportedOperationException.class, () -> r.headers().put("X", "y"));
	}

	@Test
	void withMethods_returnACopy_andLeaveTheOriginalUntouched() {
		TransportRequest original = TransportRequest.of("POST", "/x", "tok");

		TransportRequest rich = original.withHeader("X-Request-ID", "abc").withBody("image/png", new byte[]{1, 2})
				.withTimeout(Duration.ofMinutes(2));

		assertEquals("abc", rich.headers().get("X-Request-ID"));
		assertEquals("image/png", rich.contentType());
		assertEquals(Duration.ofMinutes(2), rich.timeout());
		assertTrue(original.headers().isEmpty());
		assertNull(original.body());
		assertNull(original.timeout());
	}

	@Test
	void jsonBody_isUtf8_withTheJsonContentType_andNullMeansEmpty() {
		TransportRequest r = TransportRequest.of("PUT", "/x", "tok").withJsonBody("{\"é\":1}");

		assertEquals("application/json", r.contentType());
		assertArrayEquals("{\"é\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8), r.body());
		assertArrayEquals(new byte[0], TransportRequest.of("PUT", "/x", "tok").withJsonBody(null).body());
	}

	@Test
	void aBodyNeedsAContentType() {
		TransportRequest r = TransportRequest.of("POST", "/x", "tok");

		assertThrows(IllegalArgumentException.class, () -> r.withBody(null, new byte[]{1}));
		assertThrows(IllegalArgumentException.class, () -> r.withBody(" ", new byte[]{1}));
	}

	@Test
	void body_isDefensivelyCopied_bothWays() {
		byte[] bytes = {1, 2, 3};
		TransportRequest r = TransportRequest.of("POST", "/x", "tok").withBody("application/octet-stream", bytes);

		bytes[0] = 99;
		r.body()[1] = 99;

		assertArrayEquals(new byte[]{1, 2, 3}, r.body());
	}

	@Test
	void timeout_mustBePositive() {
		TransportRequest r = TransportRequest.of("GET", "/x", "tok");

		assertThrows(IllegalArgumentException.class, () -> r.withTimeout(Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> r.withTimeout(Duration.ofSeconds(-1)));
		assertNull(r.withTimeout(null).timeout());
	}

	@Test
	void toString_neverShowsTheTokenNorTheBody() {
		TransportRequest r = TransportRequest.of("POST", "/x", "super-secret-token")
				.withJsonBody("{\"password\":\"hunter2\"}");

		assertFalse(r.toString().contains("super-secret-token"));
		assertFalse(r.toString().contains("hunter2"));
		assertTrue(r.toString().contains("POST /x"));
	}

	// ---- méthodes par défaut de HttpTransport
	// ------------------------------------------------

	/**
	 * Transport minimal : ne fournit que get/put/post, comme avant l'arrivée de
	 * send().
	 */
	private static class MinimalTransport implements HttpTransport {
		@Override
		public CompletionStage<String> get(String path, String accessToken) {
			return CompletableFuture.completedFuture("{}");
		}

		@Override
		public CompletionStage<String> put(String path, String accessToken, String jsonBody) {
			return CompletableFuture.completedFuture("{}");
		}

		@Override
		public CompletionStage<String> post(String path, String accessToken, String jsonBody) {
			return CompletableFuture.completedFuture("{}");
		}
	}

	@Test
	void aTransportWithoutSend_failsCleanly_forGenericRequestsAndDelete() {
		MinimalTransport transport = new MinimalTransport();

		CompletionException ex = assertThrows(CompletionException.class,
				() -> transport.delete("/x", "tok").toCompletableFuture().join());

		assertInstanceOf(UnsupportedOperationException.class, ex.getCause());
		assertEquals("{}", transport.get("/x", "tok").toCompletableFuture().join(), "le reste reste utilisable");
	}

	@Test
	void delete_isASendOfADeleteRequest() {
		AtomicReference<TransportRequest> seen = new AtomicReference<>();
		HttpTransport transport = new MinimalTransport() {
			@Override
			public CompletionStage<String> send(TransportRequest request) {
				seen.set(request);
				return CompletableFuture.completedFuture("{}");
			}
		};

		transport.delete("/_matrix/client/v3/x", "tok").toCompletableFuture().join();

		assertEquals("DELETE", seen.get().method());
		assertEquals("/_matrix/client/v3/x", seen.get().path());
		assertEquals("tok", seen.get().accessToken());
		assertNull(seen.get().body());
	}
}
