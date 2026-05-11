package myfluxo.adapter.http.auth;

import java.util.UUID;

/**
 * Wire shape returned by {@code GET /v1/auth/me}. Just enough for a
 * client to know "who am I and what can I do?" without exposing
 * implementation detail of the access token.
 */
public record CurrentUserResponse(UUID userId, String role) {

    public static CurrentUserResponse from(CurrentUser current) {
        return new CurrentUserResponse(current.userId().value(), current.role().name());
    }
}
