package fr.gouv.tchap.matrix.core.client;

import static fr.gouv.tchap.matrix.core.HttpTransportMocks.awaitGets;
import static fr.gouv.tchap.matrix.core.HttpTransportMocks.scriptedGet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import fr.gouv.tchap.matrix.core.sync.InMemorySyncTokenStore;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class DefaultMatrixClientAutoJoinTest {

	private static final String JOIN_PATH = "/_matrix/client/v3/rooms/%21invited%3As/join";

	/**
	 * Le premier /sync contient une invitation, les suivants rien ; le join
	 * réussit.
	 */
	private static HttpTransport serverWithInvitation() {
		HttpTransport transport = scriptedGet("{\"next_batch\":\"s1\",\"rooms\":{\"invite\":{\"!invited:s\":{}}}}",
				"{\"next_batch\":\"s2\"}");
		doReturn(CompletableFuture.completedFuture("{\"room_id\":\"!invited:s\"}")).when(transport).post(any(), any(),
				any());
		return transport;
	}

	private static DefaultMatrixClient client(HttpTransport transport, boolean autoJoin) {
		return new DefaultMatrixClient(transport, "tok", "@bot:s", new InMemorySyncTokenStore(), ev -> {
		}, new MatrixClientOptions(0, 30_000, autoJoin));
	}

	@Test
	void autoJoinEnabled_joinsInvitedRoom() {
		HttpTransport transport = serverWithInvitation();
		DefaultMatrixClient client = client(transport, true);

		client.start().toCompletableFuture().join();
		try {
			verify(transport, timeout(5_000)).post(JOIN_PATH, "tok", "{}");
		} finally {
			client.stop().toCompletableFuture().join();
		}

		verify(transport, times(1)).post(any(), any(), any());
	}

	@Test
	void autoJoinDisabled_doesNotJoin() {
		HttpTransport transport = serverWithInvitation();
		DefaultMatrixClient client = client(transport, false);

		client.start().toCompletableFuture().join();
		try {
			awaitGets(transport, 2);
		} finally {
			client.stop().toCompletableFuture().join();
		}

		verify(transport, never()).post(any(), any(), any());
	}

	@Test
	void options_rejectNonPositiveTimeout() {
		assertThrows(IllegalArgumentException.class, () -> new MatrixClientOptions(0, 0, false));
	}
}
