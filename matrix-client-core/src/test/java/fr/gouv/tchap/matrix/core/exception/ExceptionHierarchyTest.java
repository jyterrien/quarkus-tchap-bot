package fr.gouv.tchap.matrix.core.exception;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.matrix.exception.AuthenticationException;
import fr.gouv.tchap.matrix.exception.MatrixException;
import fr.gouv.tchap.matrix.exception.MatrixServerException;
import fr.gouv.tchap.matrix.exception.MatrixTransportException;
import fr.gouv.tchap.matrix.exception.RateLimitException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Toutes les erreurs du SDK sont des {@link MatrixException}, le 429 compris,
 * et chaque catégorie dit si réessayer a un sens.
 */
class ExceptionHierarchyTest {

	private static MatrixServerException server(int status) {
		return new MatrixServerException("HTTP " + status + ": {}", status, null, false);
	}

	@Test
	void everySdkException_isAMatrixException() {
		List<Throwable> all = List.of(new AuthenticationException("refused"), new MatrixTransportException("down"),
				server(500), new RateLimitException("HTTP 429", 1_000));

		for (Throwable t : all) {
			assertInstanceOf(MatrixException.class, t, t.getClass().getSimpleName());
		}
	}

	@Test
	void aSingleCatchOfMatrixException_catchesTheRateLimit() {
		boolean caught = false;
		try {
			throw new RateLimitException("HTTP 429", 2_500);
		} catch (MatrixException e) {
			caught = true;
		}

		assertTrue(caught);
	}

	@Test
	void rateLimit_isAServerExceptionWithStatus429() {
		RateLimitException rl = new RateLimitException("HTTP 429 rate limited", 2_500);

		assertInstanceOf(MatrixServerException.class, rl);
		assertEquals(429, rl.statusCode());
		assertEquals("M_LIMIT_EXCEEDED", rl.errcode());
		assertEquals(2_500L, rl.retryAfterMillis());
		assertEquals("HTTP 429 rate limited", rl.getMessage());
		assertFalse(rl.softLogout());
	}

	@Test
	void rateLimit_isNotAFinalRefusal_andNotATokenRejection() {
		RateLimitException rl = new RateLimitException("HTTP 429", 1_000);

		assertTrue(rl.isRateLimited());
		assertFalse(rl.isClientError(), "un 429 réussira après un délai : ce n'est pas un refus définitif");
		assertFalse(rl.isTokenRejected());
		assertFalse(rl.isServerError());
	}

	@Test
	void clientErrors_areFinalRefusals_exceptTheRateLimit() {
		for (int status : new int[]{400, 401, 403, 404, 409, 413}) {
			assertTrue(server(status).isClientError(), "HTTP " + status);
			assertFalse(server(status).isRateLimited(), "HTTP " + status);
		}
		assertFalse(server(429).isClientError());
		assertTrue(server(429).isRateLimited());
	}

	@Test
	void serverErrors_areNotFinalRefusals() {
		for (int status : new int[]{500, 502, 503, 504}) {
			assertTrue(server(status).isServerError(), "HTTP " + status);
			assertFalse(server(status).isClientError(), "HTTP " + status);
		}
		assertFalse(server(404).isServerError());
	}

	@Test
	void transportException_keepsItsCause() {
		Exception cause = new java.io.IOException("Connection was closed");

		MatrixException e = new MatrixTransportException("Vert.x transport error: closed", cause);

		assertSame(cause, e.getCause());
		assertFalse(e instanceof MatrixServerException, "le serveur n'a rien répondu");
	}

	@Test
	void authenticationException_isNeitherAServerNorATransportError() {
		MatrixException e = new AuthenticationException("Login refused");

		assertFalse(e instanceof MatrixServerException);
		assertFalse(e instanceof MatrixTransportException);
	}

	@Test
	void tokenRejection_isDetectedByStatusOrErrcode() {
		assertTrue(server(401).isTokenRejected());
		assertTrue(new MatrixServerException("HTTP 403", 403, "M_UNKNOWN_TOKEN", true).isTokenRejected());
		assertFalse(server(403).isTokenRejected());
	}
}
