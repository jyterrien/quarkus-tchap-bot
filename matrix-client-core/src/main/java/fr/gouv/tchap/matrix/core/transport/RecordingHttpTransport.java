package fr.gouv.tchap.matrix.core.transport;

import fr.gouv.tchap.matrix.transport.HttpTransport;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Faux transport : mémorise le dernier appel et renvoie une réponse préparée. */
public class RecordingHttpTransport implements HttpTransport {

    public String method;
    public String path;
    public String token;
    public String body;
    public String response = "{}";

    @Override
    public CompletionStage<String> get(String path, String accessToken) {
        return record("GET", path, accessToken, null);
    }

    @Override
    public CompletionStage<String> put(String path, String accessToken, String jsonBody) {
        return record("PUT", path, accessToken, jsonBody);
    }

    @Override
    public CompletionStage<String> post(String path, String accessToken, String jsonBody) {
        return record("POST", path, accessToken, jsonBody);
    }

    private CompletionStage<String> record(String m, String p, String t, String b) {
        this.method = m;
        this.path = p;
        this.token = t;
        this.body = b;
        return CompletableFuture.completedFuture(response);
    }
}
