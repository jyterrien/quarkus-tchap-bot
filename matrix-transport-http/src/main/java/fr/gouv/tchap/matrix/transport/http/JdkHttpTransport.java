package fr.gouv.tchap.matrix.transport.http;

import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.exception.RateLimitException;
import fr.gouv.tchap.matrix.transport.HttpTransport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Implémentation HttpTransport basée sur java.net.http.HttpClient (JDK 11+).
 * Aucune dépendance externe : le module peut être utilisé hors Quarkus.
 *
 * Points d'attention :
 *  - Timeout de connexion 30 s, pas de timeout global par requête
 *    (le /sync long polling peut durer plusieurs minutes).
 *  - 429 -> RateLimitException avec retry-after (1 s par défaut).
 *  - 4xx/5xx -> MatrixException.
 */
public class JdkHttpTransport implements HttpTransport {

    private final HttpClient httpClient;
    private final String homeserverUrl;

    public JdkHttpTransport(String homeserverUrl) {
        this(homeserverUrl, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build());
    }

    /** Constructeur pour injection d'un HttpClient personnalisé (tests, proxy, etc.). */
    public JdkHttpTransport(String homeserverUrl, HttpClient httpClient) {
        if (homeserverUrl == null || homeserverUrl.isBlank()) {
            throw new IllegalArgumentException("homeserverUrl is required");
        }
        this.homeserverUrl = homeserverUrl.endsWith("/")
            ? homeserverUrl.substring(0, homeserverUrl.length() - 1)
            : homeserverUrl;
        this.httpClient = httpClient;
    }

    @Override
    public CompletionStage<String> get(String path, String accessToken) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(homeserverUrl + path))
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build();
        return send(request);
    }

    @Override
    public CompletionStage<String> put(String path, String accessToken, String jsonBody) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(homeserverUrl + path))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody))
            .build();
        return send(request);
    }

    private CompletionStage<String> send(HttpRequest request) {
        CompletableFuture<String> result = new CompletableFuture<>();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .whenComplete((response, ex) -> {
                if (ex != null) {
                    result.completeExceptionally(new MatrixException("Transport error: " + ex.getMessage(), ex));
                    return;
                }
                int code = response.statusCode();
                if (code == 429) {
                    long retryAfter = parseRetryAfter(response);
                    result.completeExceptionally(new RateLimitException("HTTP 429 rate limited", retryAfter));
                } else if (code >= 400) {
                    result.completeExceptionally(new MatrixException(
                        "HTTP " + code + ": " + response.body()));
                } else {
                    result.complete(response.body());
                }
            });
        return result;
    }

    /**
     * L'en-tête HTTP Retry-After est spécifié en secondes (RFC 9110 §10.2.3).
     * On le convertit en millisecondes pour respecter le contrat de
     * RateLimitException.retryAfterMillis(). Valeur par défaut : 1 s.
     */
    private long parseRetryAfter(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After")
            .map(String::trim)
            .map(raw -> {
                try {
                    long seconds = Long.parseLong(raw);
                    return Math.max(0L, seconds) * 1000L;
                } catch (NumberFormatException e) {
                    // Retry-After peut aussi être une date HTTP-date ; non géré au MVP.
                    return 1000L;
                }
            })
            .orElse(1000L);
    }
}
