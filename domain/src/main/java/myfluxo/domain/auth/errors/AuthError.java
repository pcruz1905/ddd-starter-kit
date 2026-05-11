package myfluxo.domain.auth.errors;

import myfluxo.domain.auth.model.Permission;
import myfluxo.domain.users.model.UserId;
import myfluxo.kernel.result.DomainError;

/**
 * Every way an auth operation can fail, as a sealed sum type. Mapped to
 * Stripe-shape HTTP errors at the boundary (401 for credentials/tokens,
 * 403 for permission, 422 for input).
 *
 * <h2>Security: don't leak which step failed</h2>
 * {@link InvalidCredentials} deliberately doesn't distinguish "user
 * not found" from "wrong password". Both flows must take the same
 * time and surface the same error — otherwise an attacker can probe
 * for valid email addresses.
 */
public sealed interface AuthError extends DomainError {

    /** Wrong email or wrong password — never distinguish at this layer. */
    record InvalidCredentials() implements AuthError {}

    /** Refresh token doesn't exist, is expired, or has been revoked. */
    record InvalidRefreshToken() implements AuthError {}

    /** Refresh token was used after rotation — the entire family is now suspect. */
    record RefreshTokenReuseDetected() implements AuthError {}

    /** Caller is authenticated but lacks the required permission. */
    record Forbidden(Permission required) implements AuthError {}

    /** Caller is not authenticated on a protected route. */
    record Unauthenticated() implements AuthError {}

    /** Account exists but is inactive (suspended, etc.). */
    record AccountInactive(UserId userId) implements AuthError {}

    /** New password fails complexity / length / known-breach rules. */
    record WeakPassword(String reason) implements AuthError {}

    /** Old password doesn't match — for ChangePassword. */
    record OldPasswordMismatch() implements AuthError {}
}
