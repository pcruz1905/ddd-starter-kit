package myfluxo.application.auth.commands;

/**
 * Input for the {@code RefreshSession} use case. Carries only the
 * plaintext refresh token presented by the caller. The use case
 * HMAC-hashes it, looks up the stored token, validates state, and
 * rotates.
 */
public record RefreshSessionCommand(String refreshToken) {}
