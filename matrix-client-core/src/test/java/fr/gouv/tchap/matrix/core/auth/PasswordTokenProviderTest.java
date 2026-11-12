package fr.gouv.tchap.matrix.core.auth;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.matrix.auth.Credentials;
import fr.gouv.tchap.matrix.exception.AuthenticationException;
import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.exception.MatrixServerException;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class PasswordTokenProviderTest {

	private static final String LOGIN_PATH = "/_matrix/client/v3/login";
	private static final String REFRESH_PATH = "/_matrix/client/v3/refresh";
	private static final String LOGIN_ID = "@bot-user.fr:srv";

	/** Horloge réglable. */
	static final class TestClock extends Clock {
		private Instant now = Instant.parse("2026-11-11T10:00:00Z");

		void advance(Duration d) {
			now = now.plus(d);
		}

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	/**
	 * Transport scripté par chemin : chaque étape est une réponse (String), une
	 * erreur (Throwable) ou un CompletableFuture ; la dernière se répète.
	 */
	static final class FakeTransport implements HttpTransport {
		final Map<String, Deque<Object>> script = new HashMap<>();
		final List<String> bodies = new ArrayList<>();
		final Map<String, Integer> counts = new HashMap<>();
		final List<String> tokensSeen = new ArrayList<>();

		FakeTransport on(String path, Object... steps) {
			script.put(path, new ArrayDeque<>(List.of(steps)));
			return this;
		}

		int count(String path) {
			return counts.getOrDefault(path, 0);
		}

		@SuppressWarnings("unchecked")
		private CompletionStage<String> respond(String path, String token, String body) {
			counts.merge(path, 1, Integer::sum);
			tokensSeen.add(token);
			bodies.add(path + " " + body);
			Deque<Object> steps = script.get(path);
			Object step = steps.size() > 1 ? steps.poll() : steps.peek();
			if (step instanceof Throwable t) {
				return CompletableFuture.failedFuture(t);
			}
			if (step instanceof CompletionStage<?> stage) {
				return (CompletionStage<String>) stage;
			}
			return CompletableFuture.completedFuture((String) step);
		}

		String lastBodyOf(String path) {
			for (int i = bodies.size() - 1; i >= 0; i--) {
				if (bodies.get(i).startsWith(path + " ")) {
					return bodies.get(i).substring(path.length() + 1);
				}
			}
			return null;
		}

		@Override
		public CompletionStage<String> get(String path, String accessToken) {
			return respond(path, accessToken, "");
		}

		@Override
		public CompletionStage<String> put(String path, String accessToken, String jsonBody) {
			return respond(path, accessToken, jsonBody);
		}

		@Override
		public CompletionStage<String> post(String path, String accessToken, String jsonBody) {
			return respond(path, accessToken, jsonBody);
		}

		@Override
		public void close() {
		}
	}

	private static String loginResponse(String access, String refresh, long expiresInMs) {
		return "{\"access_token\":\"" + access + "\",\"device_id\":\"DEV1\",\"user_id\":\"@bot-user:srv\""
				+ (refresh == null ? "" : ",\"refresh_token\":\"" + refresh + "\"")
				+ (expiresInMs > 0 ? ",\"expires_in_ms\":" + expiresInMs : "") + "}";
	}

	private static String refreshResponse(String access, String refresh, long expiresInMs) {
		return "{\"access_token\":\"" + access + "\""
				+ (refresh == null ? "" : ",\"refresh_token\":\"" + refresh + "\"") + ",\"expires_in_ms\":"
				+ expiresInMs + "}";
	}

	private static MatrixServerException serverError(int status, String errcode) {
		return new MatrixServerException("HTTP " + status + ": {\"errcode\":\"" + errcode + "\"}", status, errcode,
				false);
	}

	private static Throwable failureOf(CompletionStage<String> stage) {
		CompletionException ex = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
		return ex.getCause();
	}

	private PasswordTokenProvider provider(FakeTransport transport, InMemoryCredentialStore store, TestClock clock) {
		return new PasswordTokenProvider(transport, LOGIN_ID, "s3cret", store, "my-bot", clock);
	}

	@Test
	void firstCall_logsIn_requestingARefreshToken_andReusesTheTokenAfterwards() {
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, loginResponse("at1", "rt1", 21_600_000));
		PasswordTokenProvider provider = provider(transport, new InMemoryCredentialStore(), new TestClock());

		String first = provider.accessToken().toCompletableFuture().join();
		String second = provider.accessToken().toCompletableFuture().join();

		assertEquals("at1", first);
		assertEquals("at1", second);
		assertEquals(1, transport.count(LOGIN_PATH));
		String body = transport.lastBodyOf(LOGIN_PATH);
		assertTrue(body.contains("\"type\":\"m.login.password\""), body);
		assertTrue(body.contains("\"user\":\"" + LOGIN_ID + "\""), body);
		assertTrue(body.contains("\"refresh_token\":true"), body);
		assertTrue(body.contains("\"initial_device_display_name\":\"my-bot\""), body);
		assertNull(transport.tokensSeen.get(0), "/login ne doit pas envoyer de token");
	}

	@Test
	void login_exposesTheServerUserId_whichMayDifferFromTheLoginIdentifier() {
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, loginResponse("at1", "rt1", 21_600_000));
		PasswordTokenProvider provider = provider(transport, new InMemoryCredentialStore(), new TestClock());

		assertTrue(provider.serverUserId().isEmpty());
		provider.accessToken().toCompletableFuture().join();

		assertEquals("@bot-user:srv", provider.serverUserId().orElseThrow());
	}

	@Test
	void concurrentFirstCalls_shareASingleLogin() {
		CompletableFuture<String> pending = new CompletableFuture<>();
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, pending);
		PasswordTokenProvider provider = provider(transport, new InMemoryCredentialStore(), new TestClock());

		CompletableFuture<String> a = provider.accessToken().toCompletableFuture();
		CompletableFuture<String> b = provider.accessToken().toCompletableFuture();
		CompletableFuture<String> c = provider.renew(null).toCompletableFuture();
		pending.complete(loginResponse("at1", "rt1", 21_600_000));

		assertEquals("at1", a.join());
		assertEquals("at1", b.join());
		assertEquals("at1", c.join());
		assertEquals(1, transport.count(LOGIN_PATH));
	}

	@Test
	void nearExpiry_theTokenIsRefreshedInAdvance_andTheRefreshTokenRotates() {
		TestClock clock = new TestClock();
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, loginResponse("at1", "rt1", 3_600_000))
				.on(REFRESH_PATH, refreshResponse("at2", "rt2", 3_600_000), refreshResponse("at3", "rt3", 3_600_000));
		PasswordTokenProvider provider = provider(transport, new InMemoryCredentialStore(), clock);
		assertEquals("at1", provider.accessToken().toCompletableFuture().join());

		clock.advance(Duration.ofMinutes(30));
		assertEquals("at1", provider.accessToken().toCompletableFuture().join(), "pas encore proche de l'expiration");
		assertEquals(0, transport.count(REFRESH_PATH));

		clock.advance(Duration.ofMinutes(29).plusSeconds(30)); // reste 30 s < marge
		assertEquals("at2", provider.accessToken().toCompletableFuture().join());
		assertTrue(transport.lastBodyOf(REFRESH_PATH).contains("\"refresh_token\":\"rt1\""));
		assertNull(transport.tokensSeen.get(transport.tokensSeen.size() - 1), "/refresh ne doit pas envoyer de token");

		clock.advance(Duration.ofMinutes(59).plusSeconds(30));
		assertEquals("at3", provider.accessToken().toCompletableFuture().join());
		assertTrue(transport.lastBodyOf(REFRESH_PATH).contains("\"refresh_token\":\"rt2\""),
				"le token de renouvellement reçu doit être utilisé au tour suivant");
		assertEquals(1, transport.count(LOGIN_PATH));
	}

	@Test
	void refreshResponseWithoutNewRefreshToken_keepsTheOldOne() {
		TestClock clock = new TestClock();
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, loginResponse("at1", "rt1", 3_600_000))
				.on(REFRESH_PATH, refreshResponse("at2", null, 3_600_000));
		PasswordTokenProvider provider = provider(transport, new InMemoryCredentialStore(), clock);
		provider.accessToken().toCompletableFuture().join();

		clock.advance(Duration.ofMinutes(59).plusSeconds(30));
		provider.accessToken().toCompletableFuture().join();
		clock.advance(Duration.ofMinutes(59).plusSeconds(30));
		provider.accessToken().toCompletableFuture().join();

		assertTrue(transport.lastBodyOf(REFRESH_PATH).contains("\"refresh_token\":\"rt1\""));
	}

	@Test
	void renewAfterRejection_usesTheRefreshToken() {
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, loginResponse("at1", "rt1", 21_600_000))
				.on(REFRESH_PATH, refreshResponse("at2", "rt2", 21_600_000));
		PasswordTokenProvider provider = provider(transport, new InMemoryCredentialStore(), new TestClock());
		provider.accessToken().toCompletableFuture().join();

		String renewed = provider.renew("at1").toCompletableFuture().join();

		assertEquals("at2", renewed);
		assertEquals(1, transport.count(LOGIN_PATH));
		assertEquals(1, transport.count(REFRESH_PATH));
	}

	@Test
	void renewOfATokenThatIsNoLongerCurrent_returnsTheCurrentWithoutAnyRequest() {
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, loginResponse("at1", "rt1", 21_600_000))
				.on(REFRESH_PATH, refreshResponse("at2", "rt2", 21_600_000));
		PasswordTokenProvider provider = provider(transport, new InMemoryCredentialStore(), new TestClock());
		provider.accessToken().toCompletableFuture().join();
		provider.renew("at1").toCompletableFuture().join(); // at1 -> at2

		// Une seconde requête, partie avec at1, est refusée à son tour
		String again = provider.renew("at1").toCompletableFuture().join();

		assertEquals("at2", again);
		assertEquals(1, transport.count(REFRESH_PATH));
	}

	@Test
	void refreshRefusedByTheServer_fallsBackToLogin_withTheSameDevice() {
		FakeTransport transport = new FakeTransport()
				.on(LOGIN_PATH, loginResponse("at1", "rt1", 21_600_000), loginResponse("at9", "rt9", 21_600_000))
				.on(REFRESH_PATH, serverError(401, "M_UNKNOWN_TOKEN"));
		PasswordTokenProvider provider = provider(transport, new InMemoryCredentialStore(), new TestClock());
		provider.accessToken().toCompletableFuture().join();

		String renewed = provider.renew("at1").toCompletableFuture().join();

		assertEquals("at9", renewed);
		assertEquals(2, transport.count(LOGIN_PATH));
		assertTrue(transport.lastBodyOf(LOGIN_PATH).contains("\"device_id\":\"DEV1\""),
				"le même appareil doit être réutilisé");
	}

	@Test
	void refreshFailingTransiently_doesNotLogInAgain_andLeavesTheCredentialsUsable() {
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, loginResponse("at1", "rt1", 21_600_000)).on(
				REFRESH_PATH, new MatrixException("Vert.x transport error: connection refused"),
				refreshResponse("at2", "rt2", 21_600_000));
		PasswordTokenProvider provider = provider(transport, new InMemoryCredentialStore(), new TestClock());
		provider.accessToken().toCompletableFuture().join();

		Throwable failure = failureOf(provider.renew("at1"));

		assertInstanceOf(MatrixException.class, failure);
		assertFalse(failure instanceof AuthenticationException);
		assertEquals(1, transport.count(LOGIN_PATH), "une panne réseau ne justifie pas une reconnexion");
		// Le renouvellement suivant, une fois le réseau revenu, réussit
		assertEquals("at2", provider.renew("at1").toCompletableFuture().join());
	}

	@Test
	void serverErrorOnRefresh_isTransient() {
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, loginResponse("at1", "rt1", 21_600_000))
				.on(REFRESH_PATH, serverError(503, "M_UNKNOWN"));
		PasswordTokenProvider provider = provider(transport, new InMemoryCredentialStore(), new TestClock());
		provider.accessToken().toCompletableFuture().join();

		assertInstanceOf(MatrixServerException.class, failureOf(provider.renew("at1")));
		assertEquals(1, transport.count(LOGIN_PATH));
	}

	@Test
	void noRefreshTokenIssued_renewalLogsInAgain() {
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, loginResponse("at1", null, 0),
				loginResponse("at2", null, 0));
		PasswordTokenProvider provider = provider(transport, new InMemoryCredentialStore(), new TestClock());
		provider.accessToken().toCompletableFuture().join();

		assertEquals("at2", provider.renew("at1").toCompletableFuture().join());
		assertEquals(2, transport.count(LOGIN_PATH));
		assertEquals(0, transport.count(REFRESH_PATH));
	}

	@Test
	void tokenWithoutExpiry_isNeverRefreshedInAdvance() {
		TestClock clock = new TestClock();
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, loginResponse("at1", null, 0));
		PasswordTokenProvider provider = provider(transport, new InMemoryCredentialStore(), clock);
		provider.accessToken().toCompletableFuture().join();

		clock.advance(Duration.ofDays(30));

		assertEquals("at1", provider.accessToken().toCompletableFuture().join());
		assertEquals(1, transport.count(LOGIN_PATH));
	}

	@Test
	void loginRefused_failsWithAuthenticationException_withoutLeakingThePassword() {
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, serverError(403, "M_FORBIDDEN"));
		PasswordTokenProvider provider = provider(transport, new InMemoryCredentialStore(), new TestClock());

		Throwable failure = failureOf(provider.accessToken());

		AuthenticationException auth = assertInstanceOf(AuthenticationException.class, failure);
		assertFalse(auth.getMessage().contains("s3cret"), auth.getMessage());
		assertTrue(auth.getMessage().contains("M_FORBIDDEN"), auth.getMessage());
	}

	@Test
	void loginFailingTransiently_isNotAnAuthenticationException() {
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH,
				new MatrixException("Vert.x transport error: timeout"), loginResponse("at1", "rt1", 21_600_000));
		PasswordTokenProvider provider = provider(transport, new InMemoryCredentialStore(), new TestClock());

		Throwable failure = failureOf(provider.accessToken());

		assertFalse(failure instanceof AuthenticationException);
		// Après l'échec, un nouvel appel retente (l'état « en cours » ne reste pas
		// bloqué)
		assertEquals("at1", provider.accessToken().toCompletableFuture().join());
	}

	@Test
	void credentialsAreStoredAfterLoginAndAfterEachRefresh() {
		InMemoryCredentialStore store = new InMemoryCredentialStore();
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, loginResponse("at1", "rt1", 21_600_000))
				.on(REFRESH_PATH, refreshResponse("at2", "rt2", 21_600_000));
		PasswordTokenProvider provider = provider(transport, store, new TestClock());

		provider.accessToken().toCompletableFuture().join();
		Credentials afterLogin = store.load().toCompletableFuture().join().orElseThrow();
		provider.renew("at1").toCompletableFuture().join();
		Credentials afterRefresh = store.load().toCompletableFuture().join().orElseThrow();

		assertEquals("at1", afterLogin.accessToken());
		assertEquals("rt1", afterLogin.refreshToken());
		assertEquals(LOGIN_ID, afterLogin.login());
		assertEquals("@bot-user:srv", afterLogin.userId());
		assertEquals("DEV1", afterLogin.deviceId());
		assertEquals("at2", afterRefresh.accessToken());
		assertEquals("rt2", afterRefresh.refreshToken());
	}

	@Test
	void storedCredentials_areReusedAtStartup_withoutLogin() {
		TestClock clock = new TestClock();
		InMemoryCredentialStore store = new InMemoryCredentialStore();
		store.save(new Credentials(LOGIN_ID, "@bot-user:srv", "DEV1", "stored-at", "stored-rt",
				clock.instant().plus(Duration.ofHours(3)))).toCompletableFuture().join();
		FakeTransport transport = new FakeTransport();
		PasswordTokenProvider provider = provider(transport, store, clock);

		assertEquals("stored-at", provider.accessToken().toCompletableFuture().join());
		assertEquals(0, transport.counts.size(), "aucune requête attendue");
		assertEquals("@bot-user:srv", provider.serverUserId().orElseThrow());
	}

	@Test
	void expiredStoredCredentials_areRefreshedAtStartup() {
		TestClock clock = new TestClock();
		InMemoryCredentialStore store = new InMemoryCredentialStore();
		store.save(new Credentials(LOGIN_ID, "@bot-user:srv", "DEV1", "old-at", "stored-rt",
				clock.instant().minus(Duration.ofHours(1)))).toCompletableFuture().join();
		FakeTransport transport = new FakeTransport().on(REFRESH_PATH, refreshResponse("at2", "rt2", 21_600_000));
		PasswordTokenProvider provider = provider(transport, store, clock);

		assertEquals("at2", provider.accessToken().toCompletableFuture().join());
		assertTrue(transport.lastBodyOf(REFRESH_PATH).contains("\"refresh_token\":\"stored-rt\""));
		assertEquals(0, transport.count(LOGIN_PATH));
	}

	@Test
	void storedCredentialsOfAnotherAccount_areIgnored() {
		InMemoryCredentialStore store = new InMemoryCredentialStore();
		store.save(new Credentials("@someone-else:srv", "@someone-else:srv", "DEV9", "other-at", "other-rt", null))
				.toCompletableFuture().join();
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, loginResponse("at1", "rt1", 21_600_000));
		PasswordTokenProvider provider = provider(transport, store, new TestClock());

		assertEquals("at1", provider.accessToken().toCompletableFuture().join());
		assertEquals(1, transport.count(LOGIN_PATH));
	}

	@Test
	void aStoreThatFailsToLoad_isTreatedAsEmpty() {
		InMemoryCredentialStore store = new InMemoryCredentialStore() {
			@Override
			public CompletionStage<java.util.Optional<Credentials>> load() {
				return CompletableFuture.failedFuture(new IllegalStateException("disk error"));
			}
		};
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, loginResponse("at1", "rt1", 21_600_000));

		assertEquals("at1", provider(transport, store, new TestClock()).accessToken().toCompletableFuture().join());
	}

	@Test
	void aStoreThatFailsToSave_doesNotPreventUsingTheNewToken() {
		InMemoryCredentialStore store = new InMemoryCredentialStore() {
			@Override
			public CompletionStage<Void> save(Credentials credentials) {
				return CompletableFuture.failedFuture(new IllegalStateException("disk full"));
			}
		};
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, loginResponse("at1", "rt1", 21_600_000));

		assertEquals("at1", provider(transport, store, new TestClock()).accessToken().toCompletableFuture().join());
	}

	@Test
	void credentialsToString_neverShowsTheTokens() {
		Credentials c = new Credentials(LOGIN_ID, "@u:srv", "DEV", "super-access", "super-refresh", null);

		assertFalse(c.toString().contains("super-access"));
		assertFalse(c.toString().contains("super-refresh"));
	}

	@Test
	void blankPassword_isRejectedAtConstruction() {
		assertThrows(IllegalArgumentException.class, () -> new PasswordTokenProvider(new FakeTransport(), LOGIN_ID, " ",
				new InMemoryCredentialStore(), "bot"));
	}
}
