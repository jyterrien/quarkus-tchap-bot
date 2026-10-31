package fr.gouv.tchap.quarkus.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.net.URI;
import java.util.Optional;

@ConfigMapping(prefix = "quarkus.tchap")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface TchapConfig {

    /**
     * URL du homeserver Tchap (ex: https://matrix.agent.tchap.gouv.fr).
     */
    @WithName("homeserver-url")
    URI homeserverUrl();

    /**
     * Token d'accès du bot (fourni par le homeserver).
     */
    @WithName("access-token")
    Optional<String> accessToken();

    /**
     * Identifiant Matrix du bot (ex: @monbot:tchap.gouv.fr).
     */
    @WithName("user-id")
    Optional<String> userId();

    /**
     * Configuration de la synchronisation Matrix.
     */
    SyncConfig sync();

    /**
     * Configuration du comportement du bot.
     */
    BotConfig bot();

    /**
     * Configuration du chiffrement (E2EE, hors MVP).
     */
    CryptoConfig crypto();

    interface SyncConfig {

        /**
         * Active ou désactive la synchronisation automatique.
         * Désactivée, le bot peut envoyer des messages mais ne reçoit aucun événement.
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * Timeout en millisecondes pour les requêtes /sync (long polling).
         */
        @WithDefault("30000")
        long timeout();

        /**
         * Nombre d'événements d'historique livrés par salon au premier sync
         * (0 = ne rejouer aucun ancien message).
         */
        @WithName("initial-limit")
        @WithDefault("0")
        int initialLimit();

        /**
         * Type de stockage du token de synchronisation.
         * {@code memory} : perdu à chaque redémarrage. {@code file} : voir {@code store-path}.
         * Pour un autre stockage (base de données, etc.), fournir son propre bean
         * {@code SyncTokenStore} : il remplace celui de l'extension.
         */
        @WithDefault("memory")
        StoreType store();

        /**
         * Chemin du fichier du token quand {@code store=file}.
         */
        @WithName("store-path")
        @WithDefault(".tchap/sync-token")
        String storePath();

        enum StoreType {
            MEMORY,
            FILE
        }
    }

    interface BotConfig {

        /**
         * Ignore les messages envoyés par le bot lui-même.
         */
        @WithName("ignore-self-messages")
        @WithDefault("true")
        boolean ignoreSelfMessages();

        /**
         * Rejoint automatiquement les salons auxquels le bot est invité.
         * Attention : tout utilisateur pouvant inviter le bot peut alors lui faire rejoindre un salon.
         */
        @WithName("auto-join-invited-rooms")
        @WithDefault("false")
        boolean autoJoinInvitedRooms();
    }

    interface CryptoConfig {

        /**
         * Active le chiffrement de bout en bout (non supporté au MVP).
         */
        @WithDefault("false")
        boolean enabled();
    }
}
