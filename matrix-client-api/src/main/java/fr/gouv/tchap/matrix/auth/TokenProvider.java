package fr.gouv.tchap.matrix.auth;

import java.util.concurrent.CompletionStage;

/**
 * Fournit le token d'accès à chaque requête et sait le renouveler.
 *
 * <p>
 * Les requêtes demandent le token à chaque appel (il change au fil des
 * renouvellements) ; sur un refus du serveur, le client appelle {@link #renew}
 * puis rejoue la requête une fois.
 */
public interface TokenProvider {

	/**
	 * Token à utiliser pour la prochaine requête. Une implémentation peut le
	 * renouveler à l'avance quand il est proche de l'expiration.
	 */
	CompletionStage<String> accessToken();

	/**
	 * Appelé quand le serveur a refusé {@code rejectedToken} : obtient un nouveau
	 * token. Si ce token n'est plus le courant (un autre appel l'a déjà renouvelé),
	 * renvoie le courant.
	 *
	 * @throws fr.gouv.tchap.matrix.exception.AuthenticationException
	 *             (dans l'étape échouée) si les identifiants sont refusés ou
	 *             qu'aucun moyen de renouveler n'existe : inutile de réessayer
	 */
	CompletionStage<String> renew(String rejectedToken);
}
