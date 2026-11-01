package fr.gouv.tchap.quarkus;

import fr.gouv.tchap.matrix.client.MatrixClient;
import fr.gouv.tchap.quarkus.config.TchapConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class TchapRuntimeTest {

    @Inject MatrixClient client;
    @Inject TchapConfig config;
    @Inject TchapClient tchap;

    @Test
    void clientIsInjected() {
        assertNotNull(client);
        assertNotNull(tchap);
    }

    @Test
    void configIsBound() {
        assertEquals("https://test.example.com", config.homeserverUrl().toString());
        assertTrue(config.sync().enabled());
        assertTrue(config.bot().ignoreSelfMessages());
        assertFalse(config.crypto().enabled());
    }
}
