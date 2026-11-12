package fr.gouv.tchap.quarkus.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
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
	 * Token d'accès du bot (fourni par le homeserver). Requis quand
	 * {@code quarkus.tchap.auth.mode=token} (défaut) ; ignoré en mode
	 * {@code password}.
	 */
	@WithName("access-token")
	Optional<String> accessToken();

	/**
	 * Identifiant Matrix du bot (ex: @monbot:tchap.gouv.fr). En mode
	 * {@code password}, c'est l'identifiant de connexion.
	 */
	@WithName("user-id")
	Optional<String> userId();

	/**
	 * Salons nommés : {@code quarkus.tchap.rooms."nom".room=...}. Chaque salon
	 * s'injecte avec {@code @TchapRoomName("nom") TchapRoom} et ses messages
	 * s'observent avec {@code @Observes @TchapRoomName("nom") TchapMessage}. Sans
	 * aucun salon déclaré, le client suit tous les salons du compte.
	 */
	Map<String, RoomConfig> rooms();

	/**
	 * Configuration de l'authentification (token fourni, ou connexion par mot de
	 * passe avec renouvellement automatique).
	 */
	AuthConfig auth();

	/**
	 * Configuration de la synchronisation Matrix.
	 */
	SyncConfig sync();

	/**
	 * Configuration des sondes de santé (/q/health/live et /q/health/ready).
	 */
	HealthConfig health();

	/**
	 * Configuration du comportement du bot.
	 */
	BotConfig bot();

	/**
	 * Configuration du chiffrement (E2EE, hors MVP).
	 */
	CryptoConfig crypto();

	interface RoomConfig {

		/**
		 * Identifiant (!id:serveur) ou alias (#nom:serveur) du salon.
		 */
		String room();

		/**
		 * Si vrai (défaut), l'application ne démarre pas quand ce salon ne peut pas
		 * être rejoint. Si faux, un avertissement est journalisé et le salon reste
		 * indisponible.
		 */
		@WithDefault("true")
		boolean required();
	}

	interface AuthConfig {

		/**
		 * {@code token} (défaut) : le token d'accès est fourni
		 * ({@code quarkus.tchap.access-token}) et n'est jamais renouvelé ; s'il expire,
		 * le bot s'arrête en erreur. {@code password} : le bot se connecte avec
		 * {@code quarkus.tchap.user-id} et {@code quarkus.tchap.auth.password}, puis
		 * renouvelle seul son token (refresh token, ou nouvelle connexion).
		 */
		@WithDefault("token")
		Mode mode();

		/**
		 * Mot de passe du bot (mode {@code password}). À fournir par variable
		 * d'environnement ou secret, jamais en clair dans un fichier versionné.
		 */
		Optional<String> password();

		/**
		 * Nom de l'appareil affiché pour la session du bot (mode {@code password}).
		 */
		@WithName("device-name")
		@WithDefault("quarkus-tchap-bot")
		String deviceName();

		/**
		 * Stockage des identifiants (token d'accès, refresh token, device) en mode
		 * {@code password}. {@code memory} : nouvelle connexion à chaque démarrage (un
		 * nouvel appareil à chaque fois si le serveur n'en réutilise pas). {@code file}
		 * : voir {@code store-path}. Pour un autre stockage (base de données, etc.),
		 * fournir son propre bean {@code CredentialStore} : il remplace celui de
		 * l'extension.
		 */
		@WithDefault("memory")
		StoreType store();

		/**
		 * Chemin du fichier des identifiants quand {@code store=file}. Il contient des
		 * secrets : le créer hors du dépôt, lisible par son seul propriétaire.
		 */
		@WithName("store-path")
		@WithDefault(".tchap/credentials.json")
		String storePath();

		enum Mode {
			TOKEN, PASSWORD
		}

		enum StoreType {
			MEMORY, FILE
		}
	}

	interface SyncConfig {

		/**
		 * Active ou désactive la synchronisation automatique. Désactivée, le bot peut
		 * envoyer des messages mais ne reçoit aucun événement.
		 */
		@WithDefault("true")
		boolean enabled();

		/**
		 * Timeout en millisecondes pour les requêtes /sync (long polling).
		 */
		@WithDefault("30000")
		long timeout();

		/**
		 * Nombre d'événements d'historique livrés par salon au premier sync (0 = ne
		 * rejouer aucun ancien message).
		 */
		@WithName("initial-limit")
		@WithDefault("0")
		int initialLimit();

		/**
		 * Type de stockage du token de synchronisation. {@code memory} : perdu à chaque
		 * redémarrage. {@code file} : voir {@code store-path}. Pour un autre stockage
		 * (base de données, etc.), fournir son propre bean {@code SyncTokenStore} : il
		 * remplace celui de l'extension.
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
			MEMORY, FILE
		}
	}

	interface HealthConfig {

		/**
		 * Durée maximale sans réponse /sync réussie avant que la sonde de liveness
		 * passe à DOWN (boucle de synchronisation bloquée ou homeserver injoignable
		 * depuis trop longtemps). Doit être nettement supérieure à
		 * {@code quarkus.tchap.sync.timeout} (long polling). Une orchestration qui
		 * redémarre le conteneur sur liveness DOWN le fera après cette durée.
		 */
		@WithName("sync-max-silence")
		@WithDefault("2M")
		Duration syncMaxSilence();
	}

	interface BotConfig {

		/**
		 * Ignore les messages envoyés par le bot lui-même.
		 */
		@WithName("ignore-self-messages")
		@WithDefault("true")
		boolean ignoreSelfMessages();

		/**
		 * Rejoint automatiquement les salons auxquels le bot est invité. Attention :
		 * tout utilisateur pouvant inviter le bot peut alors lui faire rejoindre un
		 * salon.
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
