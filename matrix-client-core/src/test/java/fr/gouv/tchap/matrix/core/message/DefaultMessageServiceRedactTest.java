package fr.gouv.tchap.matrix.core.message;

import static fr.gouv.tchap.matrix.core.HttpTransportMocks.answeringEmptyJson;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import fr.gouv.tchap.matrix.core.transport.MatrixHttpApi;
import fr.gouv.tchap.matrix.exception.MatrixServerException;
import fr.gouv.tchap.matrix.transport.HttpTransport;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Suppression d'un événement ({@code PUT .../redact/{eventId}/{txnId}}). */
class DefaultMessageServiceRedactTest {

	private final HttpTransport transport = answeringEmptyJson();
	private final DefaultMessageService service = new DefaultMessageService(new MatrixHttpApi(transport, "tok"),
			() -> "txn7");

	private void putReturnsEventId(String eventId) {
		doReturn(CompletableFuture.completedFuture("{\"event_id\":\"" + eventId + "\"}")).when(transport).put(any(),
				any(), any());
	}

	private String sentBody(String expectedPath) {
		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(transport).put(eq(expectedPath), eq("tok"), body.capture());
		return body.getValue();
	}

	@Test
	void redact_putsToTheRedactEndpoint_withTheEncodedEventId_andTheReason() {
		putReturnsEventId("$redaction");

		String receipt = service.redact("!room:server", "$orig", "spam").toCompletableFuture().join().eventId();

		assertEquals("$redaction", receipt);
		assertEquals("{\"reason\":\"spam\"}",
				sentBody("/_matrix/client/v3/rooms/%21room%3Aserver/redact/%24orig/txn7"));
	}

	@Test
	void redact_withoutReason_sendsAnEmptyJsonObject() {
		putReturnsEventId("$redaction");

		service.redact("!r:s", "$orig", null).toCompletableFuture().join();
		assertEquals("{}", sentBody("/_matrix/client/v3/rooms/%21r%3As/redact/%24orig/txn7"));
	}

	@Test
	void redact_withABlankReason_sendsNoReason() {
		putReturnsEventId("$redaction");

		service.redact("!r:s", "$orig", "   ").toCompletableFuture().join();

		assertEquals("{}", sentBody("/_matrix/client/v3/rooms/%21r%3As/redact/%24orig/txn7"));
	}

	@Test
	void redact_escapesTheReasonInJson() {
		putReturnsEventId("$redaction");

		service.redact("!r:s", "$orig", "dit \"non\"\nà la ligne").toCompletableFuture().join();

		assertEquals("{\"reason\":\"dit \\\"non\\\"\\nà la ligne\"}",
				sentBody("/_matrix/client/v3/rooms/%21r%3As/redact/%24orig/txn7"));
	}

	@Test
	void redact_rejectsAMissingEventId_beforeAnyRequest() {
		assertThrows(IllegalArgumentException.class, () -> service.redact("!r:s", null, "x"));
		assertThrows(IllegalArgumentException.class, () -> service.redact("!r:s", " ", "x"));

		verifyNoMoreInteractions(transport);
	}

	@Test
	void redact_withoutTheRight_failsWithTheServerRefusal() {
		doReturn(CompletableFuture.failedFuture(
				new MatrixServerException("HTTP 403: {\"errcode\":\"M_FORBIDDEN\"}", 403, "M_FORBIDDEN", false)))
				.when(transport).put(any(), any(), any());

		CompletionException ex = assertThrows(CompletionException.class,
				() -> service.redact("!r:s", "$theirs", null).toCompletableFuture().join());

		MatrixServerException failure = assertInstanceOf(MatrixServerException.class, ex.getCause());
		assertEquals("M_FORBIDDEN", failure.errcode());
		assertTrue(failure.isClientError());
	}
}
