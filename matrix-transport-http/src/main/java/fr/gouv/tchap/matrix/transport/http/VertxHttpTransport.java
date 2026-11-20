package fr.gouv.tchap.matrix.transport.http;

import static java.lang.System.Logger.Level.WARNING;

import fr.gouv.tchap.matrix.exception.MatrixTransportException;
import fr.gouv.tchap.matrix.exception.MatrixServerException;
import fr.gouv.tchap.matrix.exception.RateLimitException;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import fr.gouv.tchap.matrix.transport.TransportRequest;
import fr.gouv.tchap.matrix.transport.TransportResponse;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Implémentation HttpTransport basée sur Vert.x WebClient. Deux modes de
 * propriété :
 * <ul>
 * <li>{@link #VertxHttpTransport(Vertx, String)} : le Vertx appartient à
 * l'appelant (ex. celui de Quarkus). {@link #close()} ne ferme que le
 * WebClient.</li>
 * <li>{@link #VertxHttpTransport(String)} : le transport crée son propre Vertx
 * et le ferme dans {@link #close()}.</li>
 * </ul>
 */
public class VertxHttpTransport implements HttpTransport {

	private static final System.Logger LOG = System.getLogger(VertxHttpTransport.class.getName());
	private static final long CLOSE_TIMEOUT_SEC = 10;

	private final Vertx vertx;
	private final boolean ownsVertx;
	private final WebClient client;
	private final String homeserverUrl;
	private final HttpTransportOptions options;
	private final AtomicBoolean closed = new AtomicBoolean(false);

	/**
	 * Crée et possède son propre Vertx : appeler {@link #close()} pour l'arrêter.
	 */
	public VertxHttpTransport(String homeserverUrl) {
		this(homeserverUrl, HttpTransportOptions.defaults());
	}

	/** Comme {@link #VertxHttpTransport(String)}, avec des délais choisis. */
	public VertxHttpTransport(String homeserverUrl, HttpTransportOptions options) {
		// normalize() est évalué avant Vertx.vertx() : une URL invalide ne laisse aucun
		// Vertx orphelin
		this(normalize(homeserverUrl), Vertx.vertx(), true, options);
	}

	/** Utilise un Vertx fourni, qui reste sous la responsabilité de l'appelant. */
	public VertxHttpTransport(Vertx vertx, String homeserverUrl) {
		this(vertx, homeserverUrl, HttpTransportOptions.defaults());
	}

	/**
	 * Comme {@link #VertxHttpTransport(Vertx, String)}, avec des délais choisis.
	 */
	public VertxHttpTransport(Vertx vertx, String homeserverUrl, HttpTransportOptions options) {
		this(normalize(homeserverUrl), Objects.requireNonNull(vertx, "vertx is required"), false, options);
	}

	/**
	 * Visible pour les tests. {@code normalizedUrl} est déjà validée et sans « / »
	 * final.
	 */
	VertxHttpTransport(String normalizedUrl, Vertx vertx, boolean ownsVertx) {
		this(normalizedUrl, vertx, ownsVertx, HttpTransportOptions.defaults());
	}

	private VertxHttpTransport(String normalizedUrl, Vertx vertx, boolean ownsVertx, HttpTransportOptions options) {
		this.homeserverUrl = normalizedUrl;
		this.vertx = vertx;
		this.ownsVertx = ownsVertx;
		this.options = Objects.requireNonNull(options, "options is required");
		this.client = WebClient.create(vertx,
				new WebClientOptions().setConnectTimeout((int) options.connectTimeout().toMillis()).setKeepAlive(true));
	}

	private static String normalize(String homeserverUrl) {
		if (homeserverUrl == null || homeserverUrl.isBlank()) {
			throw new IllegalArgumentException("homeserverUrl is required");
		}
		return homeserverUrl.endsWith("/") ? homeserverUrl.substring(0, homeserverUrl.length() - 1) : homeserverUrl;
	}

	@Override
	public CompletionStage<String> get(String path, String accessToken) {
		return send(TransportRequest.of("GET", path, accessToken));
	}

	@Override
	public CompletionStage<String> put(String path, String accessToken, String jsonBody) {
		return send(TransportRequest.of("PUT", path, accessToken).withJsonBody(jsonBody));
	}

	@Override
	public CompletionStage<String> post(String path, String accessToken, String jsonBody) {
		return send(TransportRequest.of("POST", path, accessToken).withJsonBody(jsonBody));
	}

	/**
	 * Point d'entrée commun de toutes les requêtes : méthode, en-têtes, corps
	 * binaire, jeton facultatif et délai (celui de la requête, sinon celui des
	 * options). {@code send}, {@code get}, {@code put}, {@code post} et
	 * {@code delete} en découlent.
	 */
	@Override
	public CompletionStage<TransportResponse> execute(TransportRequest request) {
		Objects.requireNonNull(request, "request is required");
		long timeoutMs = request.timeout() != null ? request.timeout().toMillis() : options.requestTimeout().toMillis();
		return call(request, timeoutMs, () -> {
			HttpRequest<Buffer> http = client.requestAbs(HttpMethod.valueOf(request.method()),
					homeserverUrl + request.path());
			withAuth(http, request.accessToken());
			request.headers().forEach(http::putHeader);
			if (timeoutMs > 0) {
				http.timeout(timeoutMs);
			}
			byte[] body = request.body();
			if (body == null) {
				return http.send();
			}
			return http.putHeader("Content-Type", request.contentType()).sendBuffer(Buffer.buffer(body));
		});
	}

	/**
	 * Ferme le WebClient, puis le Vertx si ce transport en est propriétaire.
	 * Idempotent. Les appels suivants échouent avec une MatrixException.
	 */
	@Override
	public void close() {
		if (!closed.compareAndSet(false, true))
			return;
		client.close();
		if (!ownsVertx)
			return;

		Future<Void> vertxClosed = vertx.close();
		// Ne jamais bloquer un thread Vert.x en attendant la fermeture (risque
		// d'interblocage)
		if (Context.isOnVertxThread())
			return;
		try {
			vertxClosed.toCompletionStage().toCompletableFuture().get(CLOSE_TIMEOUT_SEC, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			LOG.log(WARNING, "Error while closing Vert.x", e);
		}
	}

	/**
	 * Sans token (null ou vide), aucun en-tête Authorization : {@code /login} et
	 * {@code /refresh} n'en veulent pas.
	 */
	private static <T> HttpRequest<T> withAuth(HttpRequest<T> request, String accessToken) {
		if (accessToken != null && !accessToken.isBlank()) {
			request.putHeader("Authorization", "Bearer " + accessToken);
		}
		return request;
	}

	/**
	 * Message « HTTP code: corps » ; errcode et soft_logout sont lus dans le corps
	 * JSON quand il y en a un.
	 */
	private static MatrixServerException serverError(int code, HttpResponse<Buffer> resp) {
		String errcode = null;
		boolean softLogout = false;
		try {
			Buffer body = resp.body();
			if (body != null) {
				JsonObject json = new JsonObject(body);
				errcode = json.getString("errcode");
				softLogout = Boolean.TRUE.equals(json.getBoolean("soft_logout"));
			}
		} catch (RuntimeException e) {
			// corps vide, non JSON ou champs d'un autre type : l'erreur reste utilisable
			// sans errcode
		}
		return new MatrixServerException("HTTP " + code + ": " + resp.bodyAsString(), code, errcode, softLogout);
	}

	private CompletionStage<TransportResponse> call(TransportRequest request, long timeoutMs,
			Supplier<Future<HttpResponse<Buffer>>> action) {
		if (closed.get())
			return failed(new MatrixTransportException("Transport is closed"));
		try {
			return handle(action.get(), request, timeoutMs);
		} catch (RuntimeException e) {
			// Fermeture concurrente : Vert.x peut lever l'exception de façon synchrone
			return failed(new MatrixTransportException("Vert.x transport error: " + e.getMessage(), e));
		}
	}

	private static <T> CompletionStage<T> failed(Throwable t) {
		CompletableFuture<T> f = new CompletableFuture<>();
		f.completeExceptionally(t);
		return f;
	}

	private CompletionStage<TransportResponse> handle(Future<HttpResponse<Buffer>> future, TransportRequest request,
			long timeoutMs) {
		CompletableFuture<TransportResponse> result = new CompletableFuture<>();
		future.onComplete(ar -> {
			if (ar.failed()) {
				Throwable cause = ar.cause();
				String reason = cause instanceof TimeoutException
						? "request timed out after " + timeoutMs + " ms (" + request.method() + " " + request.path()
								+ ")"
						: cause.getMessage();
				result.completeExceptionally(new MatrixTransportException("Vert.x transport error: " + reason, cause));
				return;
			}
			HttpResponse<Buffer> resp = ar.result();
			int code = resp.statusCode();
			if (code == 429) {
				result.completeExceptionally(
						new RateLimitException("HTTP 429 rate limited", parseRetryAfterMillis(resp)));
			} else if (code >= 400) {
				result.completeExceptionally(serverError(code, resp));
			} else {
				Map<String, String> headers = new LinkedHashMap<>();
				resp.headers().forEach(entry -> headers.putIfAbsent(entry.getKey(), entry.getValue()));
				Buffer body = resp.body();
				result.complete(new TransportResponse(code, headers, body == null ? new byte[0] : body.getBytes()));
			}
		});
		return result;
	}

	/**
	 * Délai avant réessai d'un 429, par ordre de priorité : 1. champ JSON
	 * {@code retry_after_ms} du corps (spec Matrix, M_LIMIT_EXCEEDED) ; 2. en-tête
	 * Retry-After en secondes (RFC 9110) ; 3. 1 s par défaut (absent, illisible ou
	 * HTTP-date).
	 */
	private static long parseRetryAfterMillis(HttpResponse<Buffer> resp) {
		Long fromBody = retryAfterFromBody(resp);
		if (fromBody != null)
			return fromBody;
		String raw = resp.getHeader("Retry-After");
		if (raw == null)
			return 1000L;
		try {
			return Math.max(0L, Long.parseLong(raw.trim())) * 1000L;
		} catch (NumberFormatException e) {
			return 1000L;
		}
	}

	private static Long retryAfterFromBody(HttpResponse<Buffer> resp) {
		try {
			Buffer body = resp.body();
			if (body == null)
				return null;
			Long ms = new JsonObject(body).getLong("retry_after_ms");
			return ms == null ? null : Math.max(0L, ms);
		} catch (RuntimeException e) {
			// corps vide, non JSON, ou champ d'un autre type : on retombe sur l'en-tête
			return null;
		}
	}
}
