package myfluxo.application.auth.commands;

/**
 * Input for the {@code Logout} use case. Revokes the specific refresh
 * token presented — not the whole family. To kill all sessions for a
 * user, use {@code ChangePassword} or call {@code RefreshTokenRepository.revokeAllForUser}
 * directly from an admin path.
 */
public record LogoutCommand(String refreshToken) {}
