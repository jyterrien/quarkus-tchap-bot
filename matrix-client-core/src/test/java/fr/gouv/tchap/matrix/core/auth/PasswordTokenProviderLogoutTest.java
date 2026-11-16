package fr.gouv.tchap.matrix.core.auth;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.matrix.auth.Credentials;
import fr.gouv.tchap.matrix.core.auth.PasswordTokenProviderTest.FakeTransport;
import fr.gouv.tchap.matrix.core.auth.PasswordTokenProviderTest.TestClock;
import fr.gouv.tchap.matrix.exception.AuthenticationException;
import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.exception.MatrixServerException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Fermeture de la session ({@code POST /logout}) par le fournisseur de token.
 */
class PasswordTokenProviderLogoutTest {

	private static final String LOGIN_PATH = "/_matrix/client/v3/login";
	private static final String REFRESH_PATH = "/_matrix/client/v3/refresh";
	private static final String LOGOUT_PATH = "/_matrix/client/v3/logout";
	private static final String LOGIN_ID = "@bot-user.fr:srv";

	/** Magasin qui compte les effacements, et peut échouer à effacer. */
	static final class TrackingStore extends InMemoryCredentialStore {
		final AtomicInteger clears = new AtomicInteger();
		boolean failToClear;

		@Override
		public CompletionStage<Void> clear() {
			clears.incrementAndGet();
			if (failToClear) {
				return CompletableFuture.failedFuture(new IllegalStateException("disk error"));
			}
			return super.clear();
		}
	}

	private static String loginResponse(String access, String refresh) {
		return "{\"access_token\":\"" + access + "\",\"device_id\":\"DEV1\",\"user_id\":\"@bot-user:srv\","
				+ "\"refresh_token\":\"" + refresh + "\",\"expires_in_ms\":21600000}";
	}

	private static String refreshResponse(String access, String refresh) {
		return "{\"access_token\":\"" + access + "\",\"refresh_token\":\"" + refresh + "\",\"expires_in_ms\":21600000}";
	}

	private static MatrixServerException unauthorized() {
		return new MatrixServerException("HTTP 401: {\"errcode\":\"M_UNKNOWN_TOKEN\"}", 401, "M_UNKNOWN_TOKEN", true);
	}

	private static PasswordTokenProvider provider(FakeTransport transport, TrackingStore store) {
		return new PasswordTokenProvider(transport, LOGIN_ID, "s3cret", store, "my-bot", new TestClock());
	}

	private static Throwable failureOf(CompletionStage<?> stage) {
		CompletionException ex = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
		return ex.getCause();
	}

	/** Fournisseur déjà connecté (token at1, refresh token rt1). */
	private static PasswordTokenProvider loggedIn(FakeTransport transport, TrackingStore store) {
		PasswordTokenProvider provider = provider(transport, store);
		provider.accessToken().toCompletableFuture().join();
		return provider;
	}

	@Test
	void logout_postsToTheLogoutEndpoint_withTheCurrentToken_andClearsTheStore() {
		TrackingStore store = new TrackingStore();
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, loginResponse("at1", "rt1")).on(LOGOUT_PATH, "{}");
		PasswordTokenProvider provider = loggedIn(transport, store);
		assertTrue(store.load().toCompletableFuture().join().isPresent());

		provider.logout().toCompletableFuture().join();

		assertEquals(1, transport.count(LOGOUT_PATH));
		assertEquals("at1", transport.tokensSeen.get(transport.tokensSeen.size() - 1));
		assertEquals(1, store.clears.get());
		assertTrue(store.load().toCompletableFuture().join().isEmpty(), "les identifiants doivent être oubliés");
		assertTrue(provider.serverUserId().isEmpty());
	}

	@Test
	void afterALogout_noTokenIsProvided_andNoNewSessionIsOpened() {
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, loginResponse("at1", "rt1")).on(LOGOUT_PATH, "{}");
		PasswordTokenProvider provider = loggedIn(transport, new TrackingStore());
		provider.logout().toCompletableFuture().join();

		assertInstanceOf(AuthenticationException.class, failureOf(provider.accessToken()));
		assertInstanceOf(AuthenticationException.class, failureOf(provider.renew("at1")));
		assertEquals(1, transport.count(LOGIN_PATH), "une requête tardive ne doit pas rouvrir une session");
	}

	@Test
	void logout_twice_sendsASingleRequest() {
		TrackingStore store = new TrackingStore();
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, loginResponse("at1", "rt1")).on(LOGOUT_PATH, "{}");
		PasswordTokenProvider provider = loggedIn(transport, store);

		provider.logout().toCompletableFuture().join();
		provider.logout().toCompletableFuture().join();

		assertEquals(1, transport.count(LOGOUT_PATH));
		assertEquals(1, store.clears.get());
	}

	@Test
	void logout_beforeAnyLogin_doesNothing() {
		TrackingStore store = new TrackingStore();
		FakeTransport transport = new FakeTransport();

		provider(transport, store).logout().toCompletableFuture().join();

		assertTrue(transport.counts.isEmpty(), "aucune requête attendue");
		assertEquals(0, store.clears.get());
	}

	@Test
	void logout_withAnExpiredAccessToken_refreshesFirst_thenClosesTheSession() {
		TrackingStore store = new TrackingStore();
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, loginResponse("at1", "rt1"))
				.on(REFRESH_PATH, refreshResponse("at2", "rt2")).on(LOGOUT_PATH, unauthorized(), "{}");
		PasswordTokenProvider provider = loggedIn(transport, store);

		provider.logout().toCompletableFuture().join();

		assertEquals(2, transport.count(LOGOUT_PATH));
		assertEquals(1, transport.count(REFRESH_PATH));
		assertEquals(1, transport.count(LOGIN_PATH), "jamais de nouvelle connexion pour se déconnecter");
		assertEquals("at2", transport.tokensSeen.get(transport.tokensSeen.size() - 1),
				"le second logout part avec le token renouvelé");
		assertEquals(1, store.clears.get());
	}

	@Test
	void logout_whenTheSessionIsAlreadyGone_isTreatedAsDone_withoutReconnecting() {
		TrackingStore store = new TrackingStore();
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, loginResponse("at1", "rt1"))
				.on(REFRESH_PATH, unauthorized()).on(LOGOUT_PATH, unauthorized());
		PasswordTokenProvider provider = loggedIn(transport, store);

		provider.logout().toCompletableFuture().join();

		assertEquals(1, transport.count(LOGIN_PATH), "ce serait créer une session pour la fermer aussitôt");
		assertEquals(1, store.clears.get());
		assertTrue(store.load().toCompletableFuture().join().isEmpty());
	}

	@Test
	void logout_withARejectedTokenAndNoRefreshToken_isTreatedAsDone() {
		TrackingStore store = new TrackingStore();
		FakeTransport transport = new FakeTransport()
				.on(LOGIN_PATH, "{\"access_token\":\"at1\",\"device_id\":\"DEV1\",\"user_id\":\"@bot-user:srv\"}")
				.on(LOGOUT_PATH, unauthorized());
		PasswordTokenProvider provider = loggedIn(transport, store);

		provider.logout().toCompletableFuture().join();

		assertEquals(1, transport.count(LOGOUT_PATH));
		assertEquals(0, transport.count(REFRESH_PATH));
		assertEquals(1, store.clears.get());
	}

	@Test
	void logout_failingTransiently_fails_andKeepsTheSessionUsable() {
		TrackingStore store = new TrackingStore();
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, loginResponse("at1", "rt1")).on(LOGOUT_PATH,
				new MatrixException("Vert.x transport error: Connection was closed"));
		PasswordTokenProvider provider = loggedIn(transport, store);

		Throwable failure = failureOf(provider.logout());

		assertInstanceOf(MatrixException.class, failure);
		assertEquals(0, store.clears.get(), "la session existe encore : on garde les identifiants");
		assertEquals("at1", provider.accessToken().toCompletableFuture().join());
	}

	@Test
	void logout_withAServerError_fails_andKeepsTheCredentials() {
		TrackingStore store = new TrackingStore();
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, loginResponse("at1", "rt1")).on(LOGOUT_PATH,
				new MatrixServerException("HTTP 503: {}", 503, null, false));
		PasswordTokenProvider provider = loggedIn(transport, store);

		assertInstanceOf(MatrixServerException.class, failureOf(provider.logout()));
		assertEquals(0, store.clears.get());
	}

	@Test
	void logout_succeeds_evenIfTheStoreCannotBeCleared() {
		TrackingStore store = new TrackingStore();
		store.failToClear = true;
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, loginResponse("at1", "rt1")).on(LOGOUT_PATH, "{}");
		PasswordTokenProvider provider = loggedIn(transport, store);

		provider.logout().toCompletableFuture().join();

		assertEquals(1, store.clears.get());
		assertInstanceOf(AuthenticationException.class, failureOf(provider.accessToken()));
	}

	@Test
	void logout_waitsForARenewalInProgress_thenUsesTheNewToken() {
		TrackingStore store = new TrackingStore();
		CompletableFuture<String> pendingRefresh = new CompletableFuture<>();
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, loginResponse("at1", "rt1"))
				.on(REFRESH_PATH, pendingRefresh).on(LOGOUT_PATH, "{}");
		PasswordTokenProvider provider = loggedIn(transport, store);
		CompletableFuture<String> renewal = provider.renew("at1").toCompletableFuture();

		CompletableFuture<Void> logout = provider.logout().toCompletableFuture();
		assertFalse(logout.isDone(), "le logout attend la fin du renouvellement");
		assertEquals(0, transport.count(LOGOUT_PATH));

		pendingRefresh.complete(refreshResponse("at2", "rt2"));

		assertEquals("at2", renewal.join());
		logout.join();
		assertEquals("at2", transport.tokensSeen.get(transport.tokensSeen.size() - 1));
	}

	@Test
	void logout_ofAStaticToken_doesNothing() {
		FakeTransport transport = new FakeTransport();

		new StaticTokenProvider("fixed").logout().toCompletableFuture().join();

		assertTrue(transport.counts.isEmpty());
	}

	@Test
	void credentialsLoadedFromTheStore_arePartOfTheSessionToClose() {
		TrackingStore store = new TrackingStore();
		store.save(new Credentials(LOGIN_ID, "@bot-user:srv", "DEV1", "stored-at", "stored-rt",
				new TestClock().instant().plusSeconds(3 * 3600))).toCompletableFuture().join();
		FakeTransport transport = new FakeTransport().on(LOGOUT_PATH, "{}");
		PasswordTokenProvider provider = provider(transport, store);
		provider.accessToken().toCompletableFuture().join(); // charge les identifiants mémorisés

		provider.logout().toCompletableFuture().join();

		assertEquals("stored-at", transport.tokensSeen.get(0));
		assertEquals(1, store.clears.get());
	}
}
