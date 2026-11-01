package fr.gouv.tchap.quarkus.deployment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Mini homeserver Matrix pour les tests : join, sync, envoi de message.
 *
 * <p>
 * {@code QuarkusUnitTest} recharge les classes de test dans un classloader
 * isolé. Un {@code static final FakeHomeserver} déclaré dans la classe de test
 * est donc instancié deux fois (un par classloader). Pour que le test voie
 * l'état réellement produit par l'application, une seule instance écoute
 * réellement : celle créée par le classloader système. L'instance du
 * classloader Quarkus est une « télécommande » qui lit l'état via les
 * endpoints {@code /_test/*}.
 *
 * <p>
 * Chaque classe de test obtient son propre serveur : au chargement par le
 * classloader système, la propriété {@value #URL_PROP} est effacée puis une
 * nouvelle instance est démarrée. Ainsi, aucun état ne fuit d'une classe de
 * test à l'autre.
 */
final class FakeHomeserver implements AutoCloseable {

    static final String URL_PROP = "tchap.test.homeserver.url";

    private static final String PREFIX = "/_matrix/client/v3/";
    private static final String TEST_PREFIX = "/_test/";

    private final List<String> joins = new CopyOnWriteArrayList<>();
    private final List<String> syncPaths = new CopyOnWriteArrayList<>();
    private final List<String> sends = new CopyOnWriteArrayList<>();

    private final HttpServer server;
    private final Set<String> forbiddenJoins;
    private final String firstSyncResponse;
    private final AtomicInteger syncCount = new AtomicInteger();

    /**
     * URL d'un serveur distant (mode télécommande) ; null si cette instance écoute.
     */
    private final String remoteUrl;

    private FakeHomeserver(String firstSyncResponse, Set<String> forbiddenJoins) throws IOException {
        this.firstSyncResponse = firstSyncResponse;
        this.forbiddenJoins = new LinkedHashSet<>(forbiddenJoins);
        this.remoteUrl = null;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-homeserver");
            t.setDaemon(true);
            return t;
        }));
        this.server.createContext(PREFIX, this::handleMatrix);
        this.server.createContext(TEST_PREFIX, this::handleTest);
        this.server.start();
        System.setProperty(URL_PROP, url());
    }

    /** Constructeur « télécommande » : n'écoute pas, interroge l'URL fournie. */
    private FakeHomeserver(String remoteUrl) {
        this.firstSyncResponse = null;
        this.forbiddenJoins = Set.of();
        this.remoteUrl = remoteUrl;
        this.server = null;
    }

    /**
     * Démarre un serveur si l'appel provient du classloader système (une fois
     * par classe de test) ; sinon réutilise l'instance déjà publiée.
     */
    static synchronized FakeHomeserver startOrReuse(String firstSyncResponse, Set<String> forbiddenJoins) {
        String existing = System.getProperty(URL_PROP);
        boolean isQuarkusLoader = isQuarkusClassLoader(FakeHomeserver.class.getClassLoader());
        System.out.println("[startOrReuse] existing=" + existing
                + " quarkus=" + isQuarkusLoader
                + " loader=" + FakeHomeserver.class.getClassLoader());
        if (existing != null) {
            if (isQuarkusLoader) {
                // Télécommande : on réutilise le serveur démarré par le classloader système
                return new FakeHomeserver(existing);
            }
            // Classloader système : nouvelle classe de test -> nouveau serveur
            System.clearProperty(URL_PROP);
        }
        try {
            return new FakeHomeserver(firstSyncResponse, forbiddenJoins);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isQuarkusClassLoader(ClassLoader cl) {
        if (cl == null || cl == ClassLoader.getSystemClassLoader()) {
            return false;
        }
        // Le classloader Quarkus (QuarkusClassLoader) n'est pas le classloader système
        return true;
    }

    String url() {
        return remoteUrl != null ? remoteUrl : "http://127.0.0.1:" + server.getAddress().getPort();
    }

    boolean isOwner() {
        return remoteUrl == null;
    }

    // ---- Lecture d'état : locale si owner, HTTP sinon ----

    List<String> joins() {
        return remoteUrl == null ? List.copyOf(joins) : fetchList("joins");
    }

    List<String> syncPaths() {
        return remoteUrl == null ? List.copyOf(syncPaths) : fetchList("syncs");
    }

    List<String> sends() {
        return remoteUrl == null ? List.copyOf(sends) : fetchList("sends");
    }

    private List<String> fetchList(String endpoint) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest req = java.net.http.HttpRequest
                    .newBuilder(java.net.URI.create(url() + TEST_PREFIX + endpoint))
                    .GET().build();
            java.net.http.HttpResponse<String> resp = client.send(req,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            return parseJsonArray(resp.body());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fetch " + endpoint + " from " + url(), e);
        }
    }

    private static List<String> parseJsonArray(String json) {
        List<String> out = new ArrayList<>();
        String s = json.trim();
        if (s.length() < 2 || s.charAt(0) != '[')
            return out;
        s = s.substring(1, s.length() - 1).trim();
        if (s.isEmpty())
            return out;
        for (String part : s.split(",")) {
            String v = part.trim();
            if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
                out.add(v.substring(1, v.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\"));
            }
        }
        return out;
    }

    // ---- Helpers de construction de réponse /sync ----

    static String messageEvent(String eventId, String body) {
        return "{\"event_id\":\"" + eventId + "\",\"sender\":\"@alice:s\",\"type\":\"m.room.message\","
                + "\"origin_server_ts\":1,\"content\":{\"msgtype\":\"m.text\",\"body\":\"" + body + "\"}}";
    }

    static String syncWith(String[]... roomEvents) {
        StringBuilder joined = new StringBuilder();
        for (String[] e : roomEvents) {
            if (joined.length() > 0)
                joined.append(',');
            joined.append('"').append(e[0]).append("\":{\"timeline\":{\"events\":[")
                    .append(messageEvent(e[1], e[2])).append("]}}");
        }
        return "{\"next_batch\":\"s1\","
                + "\"account_data\":{\"events\":[]},"
                + "\"presence\":{\"events\":[]},"
                + "\"to_device\":{\"events\":[]},"
                + "\"device_lists\":{\"changed\":[],\"left\":[]},"
                + "\"device_one_time_keys_count\":{},"
                + "\"rooms\":{\"join\":{" + joined + "}}}";
    }

    // ---- Routage Matrix ----

    private void handleMatrix(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String rawPath = ex.getRequestURI().getRawPath();
        String relative = rawPath.substring(PREFIX.length());
        byte[] requestBody = ex.getRequestBody().readAllBytes();
        System.out.println("[FakeHomeserver] " + method + " " + rawPath
                + " instance=" + System.identityHashCode(this)
                + " bodyLen=" + requestBody.length
                + " query=" + ex.getRequestURI().getRawQuery()
                + " queryLen="
                + (ex.getRequestURI().getRawQuery() == null ? 0 : ex.getRequestURI().getRawQuery().length()));

        if (method.equals("POST") && relative.startsWith("join/")) {
            String target = URLDecoder.decode(relative.substring("join/".length()), StandardCharsets.UTF_8);
            joins.add(target);
            if (forbiddenJoins.contains(target)) {
                reply(ex, 403, "{\"errcode\":\"M_FORBIDDEN\",\"error\":\"not invited\"}");
                return;
            }
            String roomId = target.startsWith("#") ? "!" + target.substring(1) : target;
            reply(ex, 200, "{\"room_id\":\"" + roomId + "\"}");
        } else if (method.equals("GET") && relative.startsWith("sync")) {
            String q = ex.getRequestURI().getRawQuery();
            String full = rawPath + (q != null ? "?" + q : "");
            syncPaths.add(URLDecoder.decode(full, StandardCharsets.UTF_8));
            int n = syncCount.incrementAndGet();
            if (n == 1) {
                reply(ex, 200, firstSyncResponse);
            } else {
                pause(200);
                reply(ex, 200, "{\"next_batch\":\"s" + n + "\"}");
            }
        } else if (method.equals("PUT") && relative.contains("/send/")) {
            sends.add(rawPath + " " + new String(requestBody, StandardCharsets.UTF_8));
            reply(ex, 200, "{\"event_id\":\"$sent\"}");
        } else {
            reply(ex, 404, "{\"errcode\":\"M_UNRECOGNIZED\"}");
        }
    }

    // ---- Routage /_test/* : exposé uniquement pour les télécommandes ----

    private void handleTest(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath().substring(TEST_PREFIX.length());
        switch (path) {
            case "joins" -> reply(ex, 200, toJson(joins));
            case "syncs" -> reply(ex, 200, toJson(syncPaths));
            case "sends" -> reply(ex, 200, toJson(sends));
            default -> reply(ex, 404, "{}");
        }
    }

    private static String toJson(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0)
                sb.append(',');
            sb.append('"').append(list.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition non atteinte en 10 s");
            }
            Thread.sleep(50);
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
            System.clearProperty(URL_PROP);
        }
    }
}