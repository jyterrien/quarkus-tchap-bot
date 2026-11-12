package fr.gouv.tchap.matrix.core.client;

import static fr.gouv.tchap.matrix.core.HttpTransportMocks.scriptedGet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import fr.gouv.tchap.matrix.auth.Session;
import fr.gouv.tchap.matrix.auth.TokenProvider;
import fr.gouv.tchap.matrix.client.ClientStatus;
import fr.gouv.tchap.matrix.core.sync.InMemorySyncTokenStore;
import fr.gouv.tchap.matrix.exception.MatrixServerException;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class DefaultMatrixClientAuthTest {

	/** Fournisseur de test : token fixe « managed », renouvellement impossible. */
	private static final TokenProvider MANAGED = new TokenProvider() {
		@Override
		public CompletionStage<String> accessToken() {
			return CompletableFuture.completedFuture("managed");
		}

		@Override
		public CompletionStage<String> renew(String rejectedToken) {
			return CompletableFuture.failedFuture(
					new fr.gouv.tchap.matrix.exception.AuthenticationException("Login refused by the homeserver"));
		}
	};

	@Test
	void verifySession_withAProvider_usesItsTokenAndLearnsTheServerUserId() {
		HttpTransport transport = scriptedGet("{\"user_id\":\"@bot-user:srv\",\"device_id\":\"DEV1\"}");
		DefaultMatrixClient client = new DefaultMatrixClient(transport, MANAGED, "@bot-user.fr:srv",
				new InMemorySyncTokenStore(), ev -> {
				}, MatrixClientOptions.defaults());

		Session session = client.verifySession().toCompletableFuture().join();

		assertEquals("@bot-user:srv", session.userId());
		assertEquals("DEV1", session.deviceId());
		assertTrue(session.isValid());
		verify(transport).get("/_matrix/client/v3/account/whoami", "managed");
	}

	@Test
	void sessionIsNotValid_untilVerified_whenTheTokenIsManaged() {
		DefaultMatrixClient client = new DefaultMatrixClient(scriptedGet("{}"), MANAGED, "@bot:srv",
				new InMemorySyncTokenStore(), ev -> {
				}, MatrixClientOptions.defaults());

		assertFalse(client.session().isValid());
	}

	@Test
	void refusedCredentials_putTheClientInFailedStatus() throws Exception {
		HttpTransport transport = scriptedGet(
				new MatrixServerException("HTTP 401: {\"errcode\":\"M_UNKNOWN_TOKEN\"}", 401, "M_UNKNOWN_TOKEN", true));
		DefaultMatrixClient client = new DefaultMatrixClient(transport, MANAGED, "@bot:srv",
				new InMemorySyncTokenStore(), ev -> {
				}, MatrixClientOptions.defaults());

		client.start().toCompletableFuture().join();
		try {
			verify(transport, timeout(5_000)).get(any(), any());
			long deadline = System.currentTimeMillis() + 5_000;
			while (client.status() != ClientStatus.FAILED && System.currentTimeMillis() < deadline) {
				Thread.sleep(20);
			}

			assertEquals(ClientStatus.FAILED, client.status());
		} finally {
			client.stop().toCompletableFuture().join();
		}
	}
}
