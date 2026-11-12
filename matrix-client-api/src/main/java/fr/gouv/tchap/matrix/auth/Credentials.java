package fr.gouv.tchap.matrix.auth;

import java.time.Instant;
import java.util.Objects;

/**
 * Identifiants d'une session Matrix : le token d'accès, et de quoi le
 * renouveler.
 *
 * @param login
 *            identifiant utilisé pour se connecter, tel que configuré. Il peut
 *            différer du {@code userId} renvoyé par le serveur (ex.
 *            {@code @prenom.nom.fr:srv} à la connexion, {@code @prenom.nom:srv}
 *            ensuite) : il sert à vérifier que des identifiants mémorisés
 *            appartiennent bien au compte configuré
 * @param userId
 *            identifiant du compte tel que le serveur le renvoie
 * @param refreshToken
 *            token de renouvellement, ou {@code null} si le serveur n'en a pas
 *            émis
 * @param expiresAt
 *            instant d'expiration du token d'accès, ou {@code null} s'il
 *            n'expire pas / est inconnu
 */
public record Credentials(String login, String userId, String deviceId, String accessToken, String refreshToken,
		Instant expiresAt) {

	public Credentials {
		Objects.requireNonNull(accessToken, "accessToken is required");
	}

	public boolean hasRefreshToken() {
		return refreshToken != null && !refreshToken.isBlank();
	}

	/** Ne laisse jamais les tokens apparaître dans un journal. */
	@Override
	public String toString() {
		return "Credentials[login=" + login + ", userId=" + userId + ", deviceId=" + deviceId
				+ ", accessToken=***, refreshToken=" + (hasRefreshToken() ? "***" : "none") + ", expiresAt=" + expiresAt
				+ "]";
	}
}
