package fr.gouv.tchap.matrix.sync;

import java.time.Instant;

/**
 * Instantané de l'état de la boucle de synchronisation /sync.
 *
 * @param startedAt           instant du démarrage de la boucle, ou {@code null} si elle n'a jamais démarré
 * @param lastSuccess         instant de la dernière réponse /sync traitée avec succès, ou {@code null}
 *                            si aucune n'a encore abouti depuis le démarrage
 * @param consecutiveFailures nombre d'échecs consécutifs depuis la dernière réussite (0 = tout va bien)
 * @param lastError           description de la dernière erreur, ou {@code null} si la dernière tentative a réussi
 */
public record SyncHealth(
    Instant startedAt,
    Instant lastSuccess,
    int consecutiveFailures,
    String lastError
) {

    /** État d'une boucle qui n'a jamais démarré. */
    public static SyncHealth notStarted() {
        return new SyncHealth(null, null, 0, null);
    }

    public boolean isStarted() {
        return startedAt != null;
    }
}
