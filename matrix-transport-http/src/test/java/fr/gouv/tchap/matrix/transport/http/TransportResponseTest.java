package fr.gouv.tchap.matrix.transport.http;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.matrix.transport.HttpTransport;
import fr.gouv.tchap.matrix.transport.TransportRequest;
import fr.gouv.tchap.matrix.transport.TransportResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class TransportResponseTest {

	@Test
	void header_lookupIgnoresCase_andTheFirstValueWins() {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", "image/png");
		headers.put("content-type", "text/plain");
		TransportResponse r = new TransportResponse(200, headers, new byte[0]);

		assertEquals("image/png", r.header("CONTENT-TYPE"));
		assertEquals("image/png", r.header("content-type"));
		assertNull(r.header("X-Missing"));
		assertNull(r.header(null));
	}

	@Test
	void body_isCopied_bothWays_andNeverNull() {
		byte[] bytes = {1, 2, 3};
		TransportResponse r = new TransportResponse(200, null, bytes);

		bytes[0] = 99;
		r.body()[1] = 99;

		assertArrayEquals(new byte[]{1, 2, 3}, r.body());
		assertArrayEquals(new byte[0], new TransportResponse(204, null, null).body());
	}

	@Test
	void bodyAsString_isUtf8() {
		TransportResponse r = new TransportResponse(200, Map.of(), "{\"é\":1}".getBytes(StandardCharsets.UTF_8));

		assertEquals("{\"é\":1}", r.bodyAsString());
	}

	@Test
	void headers_areImmutable_andTheToStringHidesTheBody() {
		TransportResponse r = new TransportResponse(200, Map.of("X", "y"), "secret-body".getBytes());

		assertThrows(UnsupportedOperationException.class, () -> r.headers().put("a", "b"));
		assertFalse(r.toString().contains("secret-body"));
		assertTrue(r.toString().contains("11 bytes"));
	}

	@Test
	void aTransportThatOnlyImplementsExecute_getsSendAndDeleteForFree() {
		@SuppressWarnings("resource")
		HttpTransport transport = new HttpTransport() {
			@Override
			public CompletionStage<String> get(String path, String accessToken) {
				return CompletableFuture.completedFuture("unused");
			}

			@Override
			public CompletionStage<String> put(String path, String accessToken, String jsonBody) {
				return CompletableFuture.completedFuture("unused");
			}

			@Override
			public CompletionStage<String> post(String path, String accessToken, String jsonBody) {
				return CompletableFuture.completedFuture("unused");
			}

			@Override
			public CompletionStage<TransportResponse> execute(TransportRequest request) {
				return CompletableFuture.completedFuture(new TransportResponse(200, Map.of(),
						(request.method() + " " + request.path()).getBytes(StandardCharsets.UTF_8)));
			}
		};

		assertEquals("DELETE /x", transport.delete("/x", "tok").toCompletableFuture().join());
		assertEquals("GET /y", transport.send(TransportRequest.of("GET", "/y", null)).toCompletableFuture().join());
	}
}
