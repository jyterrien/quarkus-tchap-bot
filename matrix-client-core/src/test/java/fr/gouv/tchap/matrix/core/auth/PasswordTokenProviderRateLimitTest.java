package fr.gouv.tchap.matrix.core.auth;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.matrix.core.auth.PasswordTokenProviderTest.FakeTransport;
import fr.gouv.tchap.matrix.core.auth.PasswordTokenProviderTest.TestClock;
import fr.gouv.tchap.matrix.exception.AuthenticationException;
import fr.gouv.tchap.matrix.exception.RateLimitException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/**
 * Un 429 est transitoire : le fournisseur ne le prend jamais pour un refus
 * définitif des identifiants (pas de « login refused », pas de reconnexion de
 * repli), et la requête suivante peut réussir.
 */
class PasswordTokenProviderRateLimitTest {

	private static final String LOGIN_PATH = "/_matrix/client/v3/login";
	private static final String REFRESH_PATH = "/_matrix/client/v3/refresh";
	private static final String LOGOUT_PATH = "/_matrix/client/v3/logout";

	private static final String LOGIN_OK = "{\"access_token\":\"at1\",\"device_id\":\"DEV1\","
			+ "\"user_id\":\"@bot:srv\",\"refresh_token\":\"rt1\",\"expires_in_ms\":21600000}";
	private static final String REFRESH_OK = "{\"access_token\":\"at2\",\"refresh_token\":\"rt2\","
			+ "\"expires_in_ms\":21600000}";

	private static PasswordTokenProvider provider(FakeTransport transport) {
		return new PasswordTokenProvider(transport, "@bot:srv", "s3cret", new InMemoryCredentialStore(), "bot",
				new TestClock());
	}

	private static Throwable failureOf(CompletionStage<?> stage) {
		return assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join()).getCause();
	}

	@Test
	void rateLimitedLogin_isNotAnAuthenticationFailure_andTheNextCallSucceeds() {
		@SuppressWarnings("resource")
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, new RateLimitException("HTTP 429", 1_000),
				LOGIN_OK);
		PasswordTokenProvider provider = provider(transport);

		Throwable failure = failureOf(provider.accessToken());

		assertInstanceOf(RateLimitException.class, failure);
		assertFalse(failure instanceof AuthenticationException);
		assertEquals("at1", provider.accessToken().toCompletableFuture().join());
	}

	@Test
	void rateLimitedRefresh_doesNotTriggerALoginFallback() {
		@SuppressWarnings("resource")
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, LOGIN_OK).on(REFRESH_PATH,
				new RateLimitException("HTTP 429", 1_000), REFRESH_OK);
		PasswordTokenProvider provider = provider(transport);
		provider.accessToken().toCompletableFuture().join();

		Throwable failure = failureOf(provider.renew("at1"));

		assertInstanceOf(RateLimitException.class, failure);
		assertEquals(1, transport.count(LOGIN_PATH), "un 429 ne justifie pas de se reconnecter");
		assertEquals("at2", provider.renew("at1").toCompletableFuture().join());
	}

	@Test
	void rateLimitedLogout_fails_andKeepsTheSession() {
		@SuppressWarnings("resource")
		FakeTransport transport = new FakeTransport().on(LOGIN_PATH, LOGIN_OK).on(LOGOUT_PATH,
				new RateLimitException("HTTP 429", 1_000), "{}");
		PasswordTokenProvider provider = provider(transport);
		provider.accessToken().toCompletableFuture().join();

		assertInstanceOf(RateLimitException.class, failureOf(provider.logout()));
		assertEquals("at1", provider.accessToken().toCompletableFuture().join(), "la session existe toujours");

		provider.logout().toCompletableFuture().join();
		assertEquals(2, transport.count(LOGOUT_PATH));
	}
}
