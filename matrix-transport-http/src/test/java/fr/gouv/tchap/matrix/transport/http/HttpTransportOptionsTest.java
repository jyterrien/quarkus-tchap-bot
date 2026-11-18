package fr.gouv.tchap.matrix.transport.http;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class HttpTransportOptionsTest {

	@Test
	void defaults_are30sToConnect_and60sPerRequest() {
		HttpTransportOptions defaults = HttpTransportOptions.defaults();

		assertEquals(Duration.ofSeconds(30), defaults.connectTimeout());
		assertEquals(Duration.ofSeconds(60), defaults.requestTimeout());
	}

	@Test
	void requestTimeout_canBeZeroToDisableIt_butNotNegative() {
		assertEquals(Duration.ZERO, HttpTransportOptions.defaults().withRequestTimeout(Duration.ZERO).requestTimeout());
		assertThrows(IllegalArgumentException.class,
				() -> HttpTransportOptions.defaults().withRequestTimeout(Duration.ofSeconds(-1)));
	}

	@Test
	void connectTimeout_mustBePositive() {
		assertThrows(IllegalArgumentException.class,
				() -> HttpTransportOptions.defaults().withConnectTimeout(Duration.ZERO));
		assertThrows(NullPointerException.class, () -> new HttpTransportOptions(null, Duration.ofSeconds(1)));
		assertThrows(NullPointerException.class, () -> new HttpTransportOptions(Duration.ofSeconds(1), null));
	}

	@Test
	void withMethods_changeOnlyTheirField() {
		HttpTransportOptions o = HttpTransportOptions.defaults().withRequestTimeout(Duration.ofSeconds(90));

		assertEquals(Duration.ofSeconds(90), o.requestTimeout());
		assertEquals(Duration.ofSeconds(30), o.connectTimeout());
	}
}
