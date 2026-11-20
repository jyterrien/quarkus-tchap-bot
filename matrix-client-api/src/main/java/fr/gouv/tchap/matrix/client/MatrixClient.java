package fr.gouv.tchap.matrix.client;

import fr.gouv.tchap.matrix.auth.Session;
import fr.gouv.tchap.matrix.message.MessageService;
import fr.gouv.tchap.matrix.room.MatrixRoom;
import fr.gouv.tchap.matrix.room.RoomService;
import java.util.concurrent.CompletionStage;

/**
 * Façade centrale du client Matrix. Indépendant de Quarkus. Référence spec :
 * Client-Server API v1.19, section "Client Authentication" et "Sync".
 */
public interface MatrixClient {

	/** Démarre le client (authentification + sync initial). */
	CompletionStage<Void> start();

	/** Arrête proprement le client. */
	CompletionStage<Void> stop();

	/**
	 * Ferme la session sur le serveur : le token est invalidé et l'appareil
	 * disparaît des sessions du compte. À appeler après {@link #stop()}. Sans effet
	 * quand le token a été fourni par l'appelant plutôt qu'obtenu par connexion
	 * avec un mot de passe. Après un logout, le client ne peut plus s'authentifier.
	 * <p>
	 * Ne pas l'appeler quand la session doit survivre à l'arrêt (identifiants
	 * persistés pour reprendre au démarrage suivant, chiffrement de bout en bout
	 * lié à l'appareil).
	 */
	CompletionStage<Void> logout();

	MessageService messages();

	/** Médias : téléverser, télécharger, envoyer un fichier dans un salon. */
	fr.gouv.tchap.matrix.media.MediaService media();

	RoomService rooms();

	Session session();

	ClientStatus status();

	/**
	 * État de la boucle de synchronisation : dernière réussite, échecs consécutifs.
	 * Contrairement à {@link #status()}, qui ne change qu'au démarrage et à
	 * l'arrêt, reflète la santé réelle de /sync.
	 */
	fr.gouv.tchap.matrix.sync.SyncHealth syncHealth();

	/** Accès restreint à un salon (identifiant résolu). Aucun appel réseau. */
	MatrixRoom room(String roomId);

	/**
	 * Restreint la synchronisation à ces salons : filtre côté serveur sur chaque
	 * /sync et rejet côté client des autres. Collection vide : tous les salons.
	 */
	void restrictToRooms(java.util.Collection<String> roomIds);

	/**
	 * Interroge le serveur (whoami) et met à jour session() avec l'identité réelle
	 * du token.
	 */
	CompletionStage<Session> verifySession();
}
