package fr.gouv.tchap.matrix.transport.http;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.matrix.exception.MatrixTransportException;
import fr.gouv.tchap.matrix.transport.TransportRequest;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Requêtes génériques (DELETE, en-têtes, corps binaire) et délais du transport
 * Vert.x.
 */
class VertxHttpTransportRequestTest {

	private static Vertx vertx;
	private HttpServer server;
	private final List<VertxHttpTransport> transports = new ArrayList<>();

	@BeforeAll
	static void startVertx() {
		vertx = Vertx.vertx();
	}

	@AfterAll
	static void stopVertx() {
		vertx.close().toCompletionStage().toCompletableFuture().join();
	}

	@AfterEach
	void stopServer() {
		transports.forEach(VertxHttpTransport::close);
		transports.clear();
		if (server != null) {
			server.close().toCompletionStage().toCompletableFuture().join();
			server = null;
		}
	}

	private String startServer(Handler<HttpServerRequest> handler) {
		server = vertx.createHttpServer().requestHandler(handler);
		server.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().join();
		return "http://127.0.0.1:" + server.actualPort();
	}

	private VertxHttpTransport transport(String url, HttpTransportOptions options) {
		VertxHttpTransport t = new VertxHttpTransport(vertx, url, options);
		transports.add(t);
		return t;
	}

	private static Throwable failureOf(CompletionStage<String> stage) {
		return assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join()).getCause();
	}

	// ---- méthodes et en-têtes
	// ---------------------------------------------------------------

	@Test
	void delete_sendsADeleteRequestWithTheBearerToken() {
		AtomicReference<String> method = new AtomicReference<>();
		AtomicReference<String> uri = new AtomicReference<>();
		AtomicReference<String> auth = new AtomicReference<>();
		String url = startServer(req -> {
			method.set(req.method().name());
			uri.set(req.uri());
			auth.set(req.getHeader("Authorization"));
			req.response().setStatusCode(200).end("{\"deleted\":true}");
		});

		String body = transport(url, HttpTransportOptions.defaults()).delete("/_matrix/client/v3/x", "tok")
				.toCompletableFuture().join();

		assertEquals("DELETE", method.get());
		assertEquals("/_matrix/client/v3/x", uri.get());
		assertEquals("Bearer tok", auth.get());
		assertEquals("{\"deleted\":true}", body);
	}

	@Test
	void send_withCustomHeadersAndABinaryBody_deliversThemUntouched() {
		AtomicReference<String> method = new AtomicReference<>();
		AtomicReference<String> contentType = new AtomicReference<>();
		AtomicReference<String> requestId = new AtomicReference<>();
		AtomicReference<byte[]> received = new AtomicReference<>();
		String url = startServer(req -> req.bodyHandler(buffer -> {
			method.set(req.method().name());
			contentType.set(req.getHeader("Content-Type"));
			requestId.set(req.getHeader("X-Request-ID"));
			received.set(buffer.getBytes());
			req.response().setStatusCode(200).end("{\"content_uri\":\"mxc://s/abc\"}");
		}));
		byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, (byte) 255, (byte) 128};

		String response = transport(url, HttpTransportOptions.defaults())
				.send(TransportRequest.of("POST", "/_matrix/media/v3/upload?filename=a.png", "tok")
						.withHeader("X-Request-ID", "req-42").withBody("image/png", png))
				.toCompletableFuture().join();

		assertEquals("POST", method.get());
		assertEquals("image/png", contentType.get());
		assertEquals("req-42", requestId.get());
		assertArrayEquals(png, received.get(), "les octets doivent arriver sans altération");
		assertEquals("{\"content_uri\":\"mxc://s/abc\"}", response);
	}

	@Test
	void send_withoutToken_sendsNoAuthorizationHeader() {
		AtomicReference<String> auth = new AtomicReference<>("unset");
		String url = startServer(req -> {
			auth.set(req.getHeader("Authorization"));
			req.response().end("{}");
		});

		transport(url, HttpTransportOptions.defaults())
				.send(TransportRequest.of("GET", "/_matrix/client/versions", null)).toCompletableFuture().join();

		assertNull(auth.get());
	}

	@Test
	void send_supportsPatch() {
		AtomicReference<String> method = new AtomicReference<>();
		String url = startServer(req -> {
			method.set(req.method().name());
			req.response().end("{}");
		});

		transport(url, HttpTransportOptions.defaults())
				.send(TransportRequest.of("PATCH", "/x", "tok").withJsonBody("{}")).toCompletableFuture().join();

		assertEquals("PATCH", method.get());
	}

	@Test
	void theOlderMethods_stillSendTheSameRequests() {
		AtomicReference<String> method = new AtomicReference<>();
		AtomicReference<String> contentType = new AtomicReference<>();
		AtomicReference<String> sent = new AtomicReference<>();
		String url = startServer(req -> req.bodyHandler(buffer -> {
			method.set(req.method().name());
			contentType.set(req.getHeader("Content-Type"));
			sent.set(buffer.toString());
			req.response().end("{}");
		}));
		VertxHttpTransport t = transport(url, HttpTransportOptions.defaults());

		t.put("/x", "tok", "{\"a\":1}").toCompletableFuture().join();
		assertEquals("PUT", method.get());
		assertEquals("application/json", contentType.get());
		assertEquals("{\"a\":1}", sent.get());

		t.post("/x", "tok", null).toCompletableFuture().join();
		assertEquals("POST", method.get());
		assertEquals("application/json", contentType.get());
		assertEquals("", sent.get(), "un corps nul donne un corps vide");
	}

	// ---- délais
	// -----------------------------------------------------------------------------

	@Test
	void aRequestThatNeverAnswers_failsAfterTheRequestTimeout_withATransportException() {
		String url = startServer(req -> {
			// ne répond jamais
		});
		VertxHttpTransport t = transport(url,
				HttpTransportOptions.defaults().withRequestTimeout(Duration.ofMillis(300)));

		long start = System.nanoTime();
		Throwable failure = failureOf(t.get("/x", "tok"));
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		MatrixTransportException transportError = assertInstanceOf(MatrixTransportException.class, failure);
		assertTrue(transportError.getMessage().toLowerCase().contains("timed out")
				|| transportError.getMessage().toLowerCase().contains("timeout"), transportError.getMessage());
		assertTrue(elapsedMs < 5_000, "la requête doit échouer vite : " + elapsedMs + " ms");
	}

	@Test
	void aRequestsOwnTimeout_replacesTheTransportOne() {
		String url = startServer(req -> {
			// ne répond jamais
		});
		VertxHttpTransport t = transport(url,
				HttpTransportOptions.defaults().withRequestTimeout(Duration.ofSeconds(30)));

		long start = System.nanoTime();
		Throwable failure = failureOf(
				t.send(TransportRequest.of("GET", "/x", "tok").withTimeout(Duration.ofMillis(300))));
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		assertInstanceOf(MatrixTransportException.class, failure);
		assertTrue(elapsedMs < 5_000, "le délai de la requête (300 ms), pas celui du transport (30 s) : " + elapsedMs);
	}

	@Test
	void aSlowButAnsweringServer_succeedsWithinTheTimeout() {
		String url = startServer(req -> vertx.setTimer(300, id -> req.response().end("{\"ok\":true}")));
		VertxHttpTransport t = transport(url,
				HttpTransportOptions.defaults().withRequestTimeout(Duration.ofSeconds(5)));

		assertEquals("{\"ok\":true}", t.get("/x", "tok").toCompletableFuture().join());
	}

	@Test
	void aLongPoll_shorterThanTheTimeout_isNotCut() {
		// Comme /sync : le serveur garde la requête ouverte sans rien envoyer, puis
		// répond
		String url = startServer(req -> vertx.setTimer(1_500, id -> req.response().end("{\"next_batch\":\"s2\"}")));
		VertxHttpTransport t = transport(url,
				HttpTransportOptions.defaults().withRequestTimeout(Duration.ofSeconds(10)));

		assertEquals("{\"next_batch\":\"s2\"}", t.get("/sync", "tok").toCompletableFuture().join());
	}

	@Test
	void theTransportClosedMeanwhile_stillFailsWithATransportException() {
		VertxHttpTransport t = transport("http://127.0.0.1:1", HttpTransportOptions.defaults());
		t.close();

		assertInstanceOf(MatrixTransportException.class, failureOf(t.send(TransportRequest.of("DELETE", "/x", "tok"))));
	}

	// ---- réponse binaire (execute)
	// ----------------------------------------------------------

	@Test
	void execute_returnsTheBinaryBodyAndTheResponseHeaders() {
		byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, (byte) 255, (byte) 128};
		String url = startServer(req -> req.response().setStatusCode(200).putHeader("Content-Type", "image/png")
				.putHeader("Content-Disposition", "attachment; filename=\"chart.png\"")
				.end(io.vertx.core.buffer.Buffer.buffer(png)));

		fr.gouv.tchap.matrix.transport.TransportResponse response = transport(url, HttpTransportOptions.defaults())
				.execute(TransportRequest.of("GET", "/_matrix/client/v1/media/download/srv/abc", "tok"))
				.toCompletableFuture().join();

		assertEquals(200, response.statusCode());
		assertArrayEquals(png, response.body(), "les octets doivent arriver sans altération");
		assertEquals("image/png", response.header("content-type"));
		assertEquals("attachment; filename=\"chart.png\"", response.header("Content-Disposition"));
	}

	@Test
	void execute_onAnErrorStatus_failsLikeSend() {
		String url = startServer(
				req -> req.response().setStatusCode(404).end("{\"errcode\":\"M_NOT_FOUND\",\"error\":\"Not found\"}"));

		Throwable failure = assertThrows(CompletionException.class,
				() -> transport(url, HttpTransportOptions.defaults()).execute(TransportRequest.of("GET", "/x", "tok"))
						.toCompletableFuture().join())
				.getCause();

		fr.gouv.tchap.matrix.exception.MatrixServerException server = assertInstanceOf(
				fr.gouv.tchap.matrix.exception.MatrixServerException.class, failure);
		assertEquals(404, server.statusCode());
		assertEquals("M_NOT_FOUND", server.errcode());
	}

	@Test
	void send_stillReadsTheBodyAsText() {
		String url = startServer(req -> req.response().end("{\"content_uri\":\"mxc://srv/abc\"}"));

		String body = transport(url, HttpTransportOptions.defaults()).send(
				TransportRequest.of("POST", "/_matrix/media/v3/upload", "tok").withBody("image/png", new byte[]{1}))
				.toCompletableFuture().join();

		assertEquals("{\"content_uri\":\"mxc://srv/abc\"}", body);
	}

	@Test
	void execute_onAnEmptyBody_returnsAnEmptyArray() {
		String url = startServer(req -> req.response().setStatusCode(204).end());

		fr.gouv.tchap.matrix.transport.TransportResponse response = transport(url, HttpTransportOptions.defaults())
				.execute(TransportRequest.of("DELETE", "/x", "tok")).toCompletableFuture().join();

		assertEquals(204, response.statusCode());
		assertEquals(0, response.body().length);
	}
}
