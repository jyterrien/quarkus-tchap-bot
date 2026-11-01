package fr.gouv.tchap.quarkus;

import io.quarkus.runtime.annotations.Recorder;
import org.jboss.logging.Logger;

/**
 * Recorder pour transmettre au runtime des valeurs calculées au build time.
 * Pour le MVP, il ne fait que journaliser le démarrage de l'extension.
 * Sera utilisé plus tard pour la sélection de stratégie crypto (E2EE).
 */
@Recorder
public class TchapRecorder {

    private static final Logger LOG = Logger.getLogger(TchapRecorder.class);

    public void logStartup(boolean cryptoEnabled) {
        LOG.infof("Tchap Bot extension active (crypto=%s)", cryptoEnabled);
    }
}
