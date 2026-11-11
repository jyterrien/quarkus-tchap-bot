package fr.gouv.tchap.quarkus.it;

import static org.junit.jupiter.api.Assertions.*;

import fr.gouv.tchap.quarkus.events.TchapEventPublisher;
import fr.gouv.tchap.quarkus.events.TchapMessage;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TchapEventPublisherTest {

	/**
	 * Observer de test. @Singleton (pas de proxy client) pour pouvoir lire ses
	 * champs. Ne réagit qu'aux événements dont l'id commence par « test- ».
	 */
	@Singleton
	static class Recorder {
		final BlockingQueue<String> delivered = new LinkedBlockingQueue<>();
		final CountDownLatch release = new CountDownLatch(1);
		volatile Boolean slowObserverOnEventLoop;

		void on(@Observes TchapMessage message) {
			switch (message.eventId()) {
				case "test-slow" -> {
					slowObserverOnEventLoop = Context.isOnEventLoopThread();
					delivered.add(message.eventId());
					try {
						release.await(5, TimeUnit.SECONDS); // observer volontairement bloquant
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
				case "test-boom" -> {
					delivered.add(message.eventId());
					throw new IllegalStateException("boom");
				}
				case "test-after" -> delivered.add(message.eventId());
				default -> {
				}
			}
		}
	}

	@Inject
	TchapEventPublisher publisher;
	@Inject
	Recorder recorder;
	@Inject
	Vertx vertx;

	private static TchapMessage msg(String eventId) {
		return new TchapMessage(eventId, "!r:s", "@u:s", "hi", Instant.now(), false, "m.text");
	}

	@Test
	void observersRunOffTheEventLoop_andPublishDoesNotWaitForThem() throws Exception {
		CountDownLatch publishReturned = new CountDownLatch(1);
		try {
			// On publie depuis un thread event loop, comme le fait la boucle /sync
			vertx.runOnContext(v -> {
				publisher.publish(msg("test-slow"));
				publishReturned.countDown();
			});

			assertTrue(publishReturned.await(2, TimeUnit.SECONDS),
					"publish() ne doit pas attendre la fin de l'observer");
			assertEquals("test-slow", recorder.delivered.poll(5, TimeUnit.SECONDS));
			assertEquals(Boolean.FALSE, recorder.slowObserverOnEventLoop,
					"l'observer ne doit pas s'exécuter sur l'event loop");
		} finally {
			recorder.release.countDown();
		}
	}

	@Test
	void observerException_doesNotPropagateNorBreakLaterDeliveries() throws Exception {
		assertDoesNotThrow(() -> publisher.publish(msg("test-boom")));
		publisher.publish(msg("test-after"));

		Set<String> received = new HashSet<>();
		received.add(recorder.delivered.poll(5, TimeUnit.SECONDS));
		received.add(recorder.delivered.poll(5, TimeUnit.SECONDS));

		assertEquals(Set.of("test-boom", "test-after"), received);
	}
}
