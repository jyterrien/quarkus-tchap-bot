package fr.gouv.tchap.quarkus.deployment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Faux homeserver Matrix (WireMock) pour les tests {@code QuarkusUnitTest} :
 * join, sync, envoi de message.
 * <p>
 * {@code QuarkusUnitTest} charge la classe de test deux fois : une fois par le
 * classloader système (JUnit), une fois par le classloader de l'application. Ce
 * dernier ne voit pas le répertoire {@code test-classes} : cette classe doit
 * donc être ajoutée à l'archive ({@code addClasses(..., FakeHomeserver.class)})
 * et existe en deux exemplaires indépendants. Seul le <b>port</b> les relie,
 * via une propriété système (la seule mémoire partagée par les deux
 * classloaders) :
 * <ul>
 * <li>le premier exemplaire (celui de JUnit) démarre le serveur et publie son
 * port : il en est propriétaire ;
 * <li>le second se contente de s'y connecter ;
 * <li>dans les deux cas, les requêtes reçues sont lues par l'API HTTP
 * d'administration de WireMock, donc par un chemin de code unique qui ne dépend
 * d'aucun état statique.
 * </ul>
 */
final class FakeHomeserver implements AutoCloseable {

	private static final String PREFIX = "/_matrix/client/v3/";

	private final String property; // propriété système du port ; null si non propriétaire
	private final WireMockServer server; // null si non propriétaire
	private final int port;
	private final WireMock admin;
	private final AtomicBoolean closed = new AtomicBoolean();

	private FakeHomeserver(String property, WireMockServer server, int port) {
		this.property = property;
		this.server = server;
		this.port = port;
		this.admin = new WireMock("127.0.0.1", port);
	}

	/**
	 * Démarre le serveur de cette classe de test, ou s'y connecte s'il est déjà
	 * démarré par l'autre exemplaire de la classe de test. {@code stubs} n'est
	 * exécuté que par le propriétaire.
	 */
	static FakeHomeserver forTest(Class<?> testClass, Consumer<FakeHomeserver> stubs) {
		String property = "tchap.test.homeserver.port." + testClass.getName();
		synchronized (System.getProperties()) {
			String existing = System.getProperty(property);
			if (existing != null) {
				return new FakeHomeserver(null, null, Integer.parseInt(existing));
			}
			WireMockServer server = new WireMockServer(options().dynamicPort().bindAddress("127.0.0.1"));
			server.start();
			System.setProperty(property, String.valueOf(server.port()));
			FakeHomeserver owner = new FakeHomeserver(property, server, server.port());
			// L'envoi d'un message réussit toujours ; les autres routes sont configurées
			// par chaque test
			server.stubFor(
					put(urlPathMatching(PREFIX + "rooms/.*/send/.*")).willReturn(okJson("{\"event_id\":\"$sent\"}")));
			stubs.accept(owner);
			// Filet de sécurité si @AfterAll ne s'exécute pas
			Runtime.getRuntime().addShutdownHook(new Thread(owner::close));
			return owner;
		}
	}

	String url() {
		return "http://127.0.0.1:" + port;
	}

	// ---- Configuration des réponses (propriétaire uniquement, depuis le callback
	// de forTest) ----

	/**
	 * Les join vers {@code target} (identifiant ou alias) réussissent et renvoient
	 * {@code roomId}.
	 */
	FakeHomeserver join(String target, String roomId) {
		server.stubFor(post(urlPathMatching(joinPath(target))).willReturn(okJson("{\"room_id\":\"" + roomId + "\"}")));
		return this;
	}

	/** Les join vers {@code target} sont refusés : 403 M_FORBIDDEN. */
	FakeHomeserver joinForbidden(String target) {
		server.stubFor(post(urlPathMatching(joinPath(target)))
				.willReturn(aResponse().withStatus(403).withHeader("Content-Type", "application/json")
						.withBody("{\"errcode\":\"M_FORBIDDEN\",\"error\":\"not invited\"}")));
		return this;
	}

	/**
	 * Le premier /sync renvoie {@code firstResponse} ; les suivants attendent 200
	 * ms et ne renvoient rien.
	 */
	FakeHomeserver firstSync(String firstResponse) {
		server.stubFor(get(urlPathEqualTo(PREFIX + "sync")).inScenario("sync").whenScenarioStateIs(Scenario.STARTED)
				.willReturn(okJson(firstResponse)).willSetStateTo("polling"));
		server.stubFor(get(urlPathEqualTo(PREFIX + "sync")).inScenario("sync").whenScenarioStateIs("polling")
				.willReturn(okJson("{\"next_batch\":\"s2\"}").withFixedDelay(200)));
		return this;
	}

	// ---- Requêtes reçues (dans l'ordre d'arrivée) ----

	/** Cibles des join reçus, décodées (ex. {@code #oncall:s}, {@code !room:s}). */
	List<String> joins() {
		return received(postRequestedFor(urlPathMatching(PREFIX + "join/.*"))).stream()
				.map(request -> decode(request.getUrl().substring((PREFIX + "join/").length()))).toList();
	}

	/** Chemin et paramètres des /sync reçus, décodés. */
	List<String> syncPaths() {
		return received(getRequestedFor(urlPathEqualTo(PREFIX + "sync"))).stream()
				.map(request -> decode(request.getUrl())).toList();
	}

	/** Envois de message reçus : « chemin décodé, espace, corps JSON ». */
	List<String> sends() {
		return received(putRequestedFor(urlPathMatching(PREFIX + "rooms/.*/send/.*"))).stream()
				.map(request -> decode(request.getUrl()) + " " + request.getBodyAsString()).toList();
	}

	private List<LoggedRequest> received(RequestPatternBuilder pattern) {
		return admin.find(pattern).stream().sorted(Comparator.comparing(LoggedRequest::getLoggedDate)).toList();
	}

	// ---- Données de test ----

	static String messageEvent(String eventId, String body) {
		return messageEvent(eventId, body, null);
	}

	/**
	 * Message ; si {@code threadRootId} n'est pas nul, c'est une réponse dans ce
	 * thread (relation m.thread).
	 */
	static String messageEvent(String eventId, String body, String threadRootId) {
		String relation = threadRootId == null
				? ""
				: ",\"m.relates_to\":{\"rel_type\":\"m.thread\",\"event_id\":\"" + threadRootId + "\"}";
		return "{\"event_id\":\"" + eventId + "\",\"sender\":\"@alice:s\",\"type\":\"m.room.message\","
				+ "\"origin_server_ts\":1,\"content\":{\"msgtype\":\"m.text\",\"body\":\"" + body + "\"" + relation
				+ "}}";
	}

	/**
	 * Réponse /sync dont chaque salon contient un message :
	 * {@code {roomId, eventId, body}}, ou {@code {roomId,
	 * eventId, body, threadRootId}} pour une réponse dans un thread.
	 */
	static String syncWith(String[]... roomEvents) {
		StringBuilder joined = new StringBuilder();
		for (String[] e : roomEvents) {
			if (joined.length() > 0) {
				joined.append(',');
			}
			joined.append('"').append(e[0]).append("\":{\"timeline\":{\"events\":[")
					.append(messageEvent(e[1], e[2], e.length > 3 ? e[3] : null)).append("]}}");
		}
		return "{\"next_batch\":\"s1\"," + "\"account_data\":{\"events\":[]}," + "\"presence\":{\"events\":[]},"
				+ "\"to_device\":{\"events\":[]}," + "\"device_lists\":{\"changed\":[],\"left\":[]},"
				+ "\"device_one_time_keys_count\":{}," + "\"rooms\":{\"join\":{" + joined + "}}}";
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

	/**
	 * Arrête le serveur s'il en est propriétaire ; sans effet pour l'autre
	 * exemplaire.
	 */
	@Override
	public void close() {
		if (server != null && closed.compareAndSet(false, true)) {
			synchronized (System.getProperties()) {
				System.clearProperty(property);
			}
			server.stop();
		}
	}

	// ---- Utilitaires ----

	/**
	 * Chemin d'un join : la cible est encodée comme le fait le client ; on accepte
	 * aussi la forme décodée.
	 */
	private static String joinPath(String target) {
		String encoded = URLEncoder.encode(target, StandardCharsets.UTF_8);
		return PREFIX + "join/(" + Pattern.quote(encoded) + "|" + Pattern.quote(target) + ")";
	}

	private static String decode(String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}
}
