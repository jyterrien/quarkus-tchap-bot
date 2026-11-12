package fr.gouv.tchap.matrix.core.transport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import fr.gouv.tchap.matrix.auth.TokenProvider;
import fr.gouv.tchap.matrix.exception.AuthenticationException;
import fr.gouv.tchap.matrix.exception.MatrixServerException;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Le token vient du TokenProvider à chaque appel ; un refus (401) déclenche un
 * renouvellement puis UN seul nouvel essai.
 */
class MatrixHttpApiAuthTest {

	private static final String WHOAMI = "/_matrix/client/v3/account/whoami";

	/** Fournisseur dont le token change à chaque renouvellement : t1, t2, ... */
	static final class CountingProvider implements TokenProvider {
		final AtomicInteger renewals = new AtomicInteger();
		final List<String> rejected = new ArrayList<>();
		private volatile String current = "t1";
		private final Throwable renewalFailure;

		CountingProvider() {
			this(null);
		}

		CountingProvider(Throwable renewalFailure) {
			this.renewalFailure = renewalFailure;
		}

		@Override
		public CompletionStage<String> accessToken() {
			return CompletableFuture.completedFuture(current);
		}

		@Override
		public CompletionStage<String> renew(String rejectedToken) {
			rejected.add(rejectedToken);
			if (renewalFailure != null) {
				return CompletableFuture.failedFuture(renewalFailure);
			}
			current = "t" + (renewals.incrementAndGet() + 1);
			return CompletableFuture.completedFuture(current);
		}
	}

	private static MatrixServerException unauthorized() {
		return new MatrixServerException("HTTP 401: {\"errcode\":\"M_UNKNOWN_TOKEN\"}", 401, "M_UNKNOWN_TOKEN", true);
	}

	/**
	 * GET qui échoue avec {@code failure} tant que le token est « t1 », puis
	 * réussit.
	 */
	private static HttpTransport rejectingTheFirstToken(Throwable failure) {
		HttpTransport transport = mock(HttpTransport.class);
		doAnswer(inv -> "t1".equals(inv.getArgument(1))
				? CompletableFuture.<String>failedFuture(failure)
				: CompletableFuture.completedFuture("{\"user_id\":\"@bot:srv\"}")).when(transport).get(any(), any());
		return transport;
	}

	@Test
	void everyCall_asksTheProviderForTheToken() {
		HttpTransport transport = mock(HttpTransport.class);
		doAnswer(inv -> CompletableFuture.completedFuture("{}")).when(transport).get(any(), any());
		MatrixHttpApi api = new MatrixHttpApi(transport, new CountingProvider());

		api.whoami().toCompletableFuture().join();

		verify(transport).get(WHOAMI, "t1");
	}

	@Test
	void rejectedToken_isRenewedAndTheRequestReplayedOnce() {
		HttpTransport transport = rejectingTheFirstToken(unauthorized());
		CountingProvider provider = new CountingProvider();
		MatrixHttpApi api = new MatrixHttpApi(transport, provider);

		String body = api.whoami().toCompletableFuture().join();

		assertEquals("{\"user_id\":\"@bot:srv\"}", body);
		assertEquals(List.of("t1"), provider.rejected, "le token refusé doit être signalé au fournisseur");
		verify(transport).get(WHOAMI, "t1");
		verify(transport).get(WHOAMI, "t2");
	}

	@Test
	void rejectedToken_isAlsoRenewedOnPost() {
		HttpTransport transport = mock(HttpTransport.class);
		doAnswer(inv -> "t1".equals(inv.getArgument(1))
				? CompletableFuture.<String>failedFuture(unauthorized())
				: CompletableFuture.completedFuture("{}")).when(transport).post(any(), any(), any());
		MatrixHttpApi api = new MatrixHttpApi(transport, new CountingProvider());

		api.joinRoom("!r:srv").toCompletableFuture().join();

		verify(transport).post(any(), org.mockito.ArgumentMatchers.eq("t1"), any());
		verify(transport).post(any(), org.mockito.ArgumentMatchers.eq("t2"), any());
	}

	@Test
	void rejectedToken_isAlsoRenewedOnPut() {
		HttpTransport transport = mock(HttpTransport.class);
		doAnswer(inv -> "t1".equals(inv.getArgument(1))
				? CompletableFuture.<String>failedFuture(unauthorized())
				: CompletableFuture.completedFuture("{\"event_id\":\"$e\"}")).when(transport).put(any(), any(), any());
		MatrixHttpApi api = new MatrixHttpApi(transport, new CountingProvider());

		api.sendMessage("!r:srv", "txn1", "{}").toCompletableFuture().join();

		verify(transport, times(2)).put(any(), any(), any());
	}

	@Test
	void secondRefusal_isPropagated_withoutAnotherRenewal() {
		HttpTransport transport = mock(HttpTransport.class);
		doAnswer(inv -> CompletableFuture.<String>failedFuture(unauthorized())).when(transport).get(any(), any());
		CountingProvider provider = new CountingProvider();
		MatrixHttpApi api = new MatrixHttpApi(transport, provider);

		CompletionException ex = assertThrows(CompletionException.class,
				() -> api.whoami().toCompletableFuture().join());

		assertInstanceOf(MatrixServerException.class, ex.getCause());
		assertEquals(1, provider.renewals.get(), "un seul renouvellement par requête");
		verify(transport, times(2)).get(any(), any());
	}

	@Test
	void otherErrors_doNotTriggerARenewal() {
		HttpTransport transport = rejectingTheFirstToken(new MatrixServerException("HTTP 500: {}", 500, null, false));
		CountingProvider provider = new CountingProvider();
		MatrixHttpApi api = new MatrixHttpApi(transport, provider);

		assertThrows(CompletionException.class, () -> api.whoami().toCompletableFuture().join());

		assertEquals(0, provider.renewals.get());
		verify(transport, times(1)).get(any(), any());
	}

	@Test
	void m_unknownToken_isTreatedAsARefusal_evenWithAnotherStatus() {
		MatrixServerException odd = new MatrixServerException("HTTP 403: {}", 403, "M_UNKNOWN_TOKEN", false);
		HttpTransport transport = rejectingTheFirstToken(odd);
		CountingProvider provider = new CountingProvider();

		new MatrixHttpApi(transport, provider).whoami().toCompletableFuture().join();

		assertEquals(1, provider.renewals.get());
	}

	@Test
	void renewalFailure_isPropagated_andTheRequestIsNotReplayed() {
		HttpTransport transport = rejectingTheFirstToken(unauthorized());
		AuthenticationException refused = new AuthenticationException("Login refused");
		MatrixHttpApi api = new MatrixHttpApi(transport, new CountingProvider(refused));

		CompletionException ex = assertThrows(CompletionException.class,
				() -> api.whoami().toCompletableFuture().join());

		assertSame(refused, ex.getCause());
		verify(transport, times(1)).get(any(), any());
		verify(transport, never()).get(any(), org.mockito.ArgumentMatchers.eq("t2"));
	}

	@Test
	void staticToken_isUsedAsIs_andNeverRenewable() {
		HttpTransport transport = mock(HttpTransport.class);
		doAnswer(inv -> CompletableFuture.<String>failedFuture(unauthorized())).when(transport).get(any(), any());
		MatrixHttpApi api = new MatrixHttpApi(transport, "fixed");

		CompletionException ex = assertThrows(CompletionException.class,
				() -> api.whoami().toCompletableFuture().join());

		assertInstanceOf(AuthenticationException.class, ex.getCause());
		verify(transport, times(1)).get(WHOAMI, "fixed");
	}
}
