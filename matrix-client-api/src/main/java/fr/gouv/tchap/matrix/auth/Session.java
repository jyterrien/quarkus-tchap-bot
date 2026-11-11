package fr.gouv.tchap.matrix.auth;

/**
 * Représente une session authentifiée. Correspond aux champs retournés par
 * /login : user_id, access_token, device_id.
 */
public interface Session {
	String userId(); // ex: "@bot:example.com"

	String deviceId(); // identifiant d'appareil Matrix

	boolean isValid(); // le token est-il considéré valide ?
}
