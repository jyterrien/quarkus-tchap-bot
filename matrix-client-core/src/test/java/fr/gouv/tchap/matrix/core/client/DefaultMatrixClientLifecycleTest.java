package fr.gouv.tchap.matrix.core.client;

import fr.gouv.tchap.matrix.client.ClientStatus;
import fr.gouv.tchap.matrix.core.sync.InMemorySyncTokenStore;
import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static fr.gouv.tchap.matrix.core.HttpTransportMocks.awaitGets;
import static fr.gouv.tchap.matrix.core.HttpTransportMocks.scriptedGet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DefaultMatrixClientLifecycleTest {

    @Test
    void status_followsTheLifecycle() {
        HttpTransport transport = scriptedGet("{\"next_batch\":\"s1\"}");
        DefaultMatrixClient client = new DefaultMatrixClient(transport, "tok", "@bot:s",
            new InMemorySyncTokenStore(), ev -> {});
        assertEquals(ClientStatus.NEW, client.status());

        client.start().toCompletableFuture().join();
        try {
            assertEquals(ClientStatus.RUNNING, client.status());
        } finally {
            client.stop().toCompletableFuture().join();
        }

        assertEquals(ClientStatus.STOPPED, client.status());
    }

    @Test
    void autoJoinFailure_doesNotStopTheClient() {
        HttpTransport transport = scriptedGet(
            "{\"next_batch\":\"s1\",\"rooms\":{\"invite\":{\"!invited:s\":{}}}}",
            "{\"next_batch\":\"s2\"}");
        doReturn(CompletableFuture.failedFuture(new MatrixException("HTTP 403")))
            .when(transport).post(any(), any(), any());
        DefaultMatrixClient client = new DefaultMatrixClient(transport, "tok", "@bot:s",
            new InMemorySyncTokenStore(), ev -> {}, new MatrixClientOptions(0, 30_000, true));

        client.start().toCompletableFuture().join();
        try {
            // le 3e GET n'a lieu que si la boucle a survécu à l'échec du join
            awaitGets(transport, 3);
            assertEquals(ClientStatus.RUNNING, client.status());
        } finally {
            client.stop().toCompletableFuture().join();
        }

        verify(transport, times(1)).post(any(), any(), any());
    }
}
