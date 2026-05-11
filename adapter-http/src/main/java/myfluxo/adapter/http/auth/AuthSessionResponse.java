package myfluxo.adapter.http.auth;

import myfluxo.application.auth.AuthSession;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape returned by register / login / refresh.
 *
 * <p>{@code refreshToken} is the plaintext value — the only time the
 * server emits it. Clients store it (httpOnly cookie or secure storage)
 * and present it back on /refresh. The server has only its hash.
 */
public record AuthSessionResponse(
    UUID userId,
    String role,
    String accessToken,
    Instant accessTokenExpiresAt,
    String refreshToken,
    Instant refreshTokenExpiresAt
) {

    public static AuthSessionResponse from(AuthSession session) {
        return new AuthSessionResponse(
            session.userId().value(),
            session.role().name(),
            session.accessToken().value(),
            session.accessToken().expiresAt(),
            session.refreshTokenPlaintext(),
            session.refreshTokenExpiresAt()
        );
    }
}
