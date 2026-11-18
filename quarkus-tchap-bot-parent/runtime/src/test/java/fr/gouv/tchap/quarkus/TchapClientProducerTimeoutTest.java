package fr.gouv.tchap.quarkus;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Le délai de requête doit laisser la place au long polling de /sync. */
class TchapClientProducerTimeoutTest {

	@Test
	void aConfiguredTimeoutThatFitsTheLongPoll_isKept() {
		// long polling de 30 s : il faut au moins 30 s + 30 s de marge
		assertEquals(Duration.ofSeconds(60),
				TchapClientProducer.effectiveRequestTimeout(Duration.ofSeconds(60), 30_000));
		assertEquals(Duration.ofSeconds(120),
				TchapClientProducer.effectiveRequestTimeout(Duration.ofSeconds(120), 30_000));
	}

	@Test
	void aTimeoutShorterThanTheLongPoll_isRaisedToTheLongPollPlusAMargin() {
		assertEquals(Duration.ofSeconds(60),
				TchapClientProducer.effectiveRequestTimeout(Duration.ofSeconds(10), 30_000));
		assertEquals(Duration.ofSeconds(60),
				TchapClientProducer.effectiveRequestTimeout(Duration.ofSeconds(30), 30_000));
	}

	@Test
	void aLongerLongPoll_raisesTheTimeoutAccordingly() {
		assertEquals(Duration.ofSeconds(90),
				TchapClientProducer.effectiveRequestTimeout(Duration.ofSeconds(60), 60_000));
	}

	@Test
	void aZeroTimeout_meansNoTimeout_andStaysZero() {
		assertEquals(Duration.ZERO, TchapClientProducer.effectiveRequestTimeout(Duration.ZERO, 30_000));
	}

	@Test
	void aSyncTimeoutOfZero_stillKeepsTheMargin() {
		assertEquals(Duration.ofSeconds(30), TchapClientProducer.effectiveRequestTimeout(Duration.ofSeconds(5), 0));
	}
}
