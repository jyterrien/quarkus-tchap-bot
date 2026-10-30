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
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * Timeout en millisecondes pour les requêtes /sync (long polling).
         */
        @WithDefault("30000")
        long timeout();

        /**
         * Nombre initial d'événements récupérés lors du premier sync.
         */
        @WithName("initial-limit")
        @WithDefault("20")
        int initialLimit();

        /**
         * Type de stockage du token de synchronisation (memory, file, database).
         */
        @WithDefault("memory")
        String store();
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
