package fr.gouv.tchap.matrix.core;

import fr.gouv.tchap.matrix.transport.HttpTransport;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** Idiomes Mockito partagés pour simuler {@link HttpTransport} dans les tests. */
public final class HttpTransportMocks {

    private HttpTransportMocks() {
    }

    /** Transport qui répond « {} » à tout appel (get, put, post). */
    public static HttpTransport answeringEmptyJson() {
        return mock(HttpTransport.class, invocation -> CompletableFuture.completedFuture("{}"));
    }

    /**
     * Transport dont les GET renvoient les étapes dans l'ordre ; la dernière se répète.
     * Une étape est une réponse (String) ou une erreur (Throwable).
     * put et post ne sont pas simulés (ils renvoient null).
     */
    public static HttpTransport scriptedGet(Object... steps) {
        if (steps.length == 0) {
            throw new IllegalArgumentException("au moins une étape est requise");
        }
        HttpTransport transport = mock(HttpTransport.class);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            Object step = steps[Math.min(calls.getAndIncrement(), steps.length - 1)];
            return step instanceof Throwable error
                ? CompletableFuture.<String>failedFuture(error)
                : CompletableFuture.completedFuture((String) step);
        }).when(transport).get(any(), any());
        return transport;
    }

    /** Attend (5 s au plus) que le transport ait reçu au moins {@code count} GET. */
    public static void awaitGets(HttpTransport transport, int count) {
        awaitGets(transport, count, 5_000);
    }

    public static void awaitGets(HttpTransport transport, int count, long timeoutMs) {
        verify(transport, timeout(timeoutMs).atLeast(count)).get(any(), any());
    }

    /** Chemins des GET reçus, dans l'ordre. À appeler une fois le moteur arrêté. */
    public static List<String> getPaths(HttpTransport transport) {
        ArgumentCaptor<String> paths = ArgumentCaptor.forClass(String.class);
        verify(transport, atLeastOnce()).get(paths.capture(), any());
        return paths.getAllValues();
    }
}
