package fr.gouv.tchap.matrix.core.auth;

import fr.gouv.tchap.matrix.auth.TokenProvider;
import fr.gouv.tchap.matrix.exception.AuthenticationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Token fourni par l'appelant, utilisé tel quel. Il ne peut pas être renouvelé
 * : s'il est refusé, {@link #renew} échoue avec une
 * {@link AuthenticationException}.
 */
public final class StaticTokenProvider implements TokenProvider {

	private final String token;

	public StaticTokenProvider(String token) {
		this.token = token;
	}

	@Override
	public CompletionStage<String> accessToken() {
		return CompletableFuture.completedFuture(token);
	}

	@Override
	public CompletionStage<String> renew(String rejectedToken) {
		return CompletableFuture.failedFuture(new AuthenticationException(
				"The access token was rejected by the homeserver and no login credentials are available to renew it"));
	}
}
