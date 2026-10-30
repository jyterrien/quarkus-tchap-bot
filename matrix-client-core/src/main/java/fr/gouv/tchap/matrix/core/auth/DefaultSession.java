package fr.gouv.tchap.matrix.core.auth;

import fr.gouv.tchap.matrix.auth.Session;

public record DefaultSession(String userId, String deviceId, String accessToken) implements Session {

    @Override
    public boolean isValid() {
        return accessToken != null && !accessToken.isBlank();
    }
}