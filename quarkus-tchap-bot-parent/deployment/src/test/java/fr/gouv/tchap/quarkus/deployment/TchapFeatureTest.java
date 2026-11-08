package fr.gouv.tchap.quarkus.deployment;

import io.quarkus.test.QuarkusExtensionTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TchapFeatureTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
        .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
            .addAsResource("application.properties"));

    @Test
    void extensionLoads() {
        assertTrue(true);
    }
}