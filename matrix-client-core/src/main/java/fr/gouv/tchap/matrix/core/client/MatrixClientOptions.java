package fr.gouv.tchap.matrix.core.client;

import fr.gouv.tchap.matrix.core.sync.SyncEngine;

/**
 * Options de comportement du client.
 *
 * @param initialLimit    événements d'historique par salon livrés au premier /sync
 *                        (0 = aucun ; valeur négative = comportement par défaut du serveur)
 * @param syncTimeoutMs   durée du long polling /sync, en millisecondes (&gt; 0)
 * @param autoJoinInvites rejoint automatiquement les salons où le bot est invité
 */
public record MatrixClientOptions(int initialLimit, long syncTimeoutMs, boolean autoJoinInvites) {

    public MatrixClientOptions {
        if (syncTimeoutMs <= 0) {
            throw new IllegalArgumentException("syncTimeoutMs must be > 0, was " + syncTimeoutMs);
        }
    }

    /** Aucun historique rejoué, timeout de 30 s, pas d'auto-join. */
    public static MatrixClientOptions defaults() {
        return new MatrixClientOptions(0, SyncEngine.DEFAULT_SYNC_TIMEOUT_MS, false);
    }
}
