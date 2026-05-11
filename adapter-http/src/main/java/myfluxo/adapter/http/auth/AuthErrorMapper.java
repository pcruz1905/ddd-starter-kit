package myfluxo.adapter.http.auth;

import io.helidon.http.Status;
import myfluxo.adapter.http.ErrorResponse;
import myfluxo.adapter.http.idempotency.HttpResult;
import myfluxo.domain.auth.errors.AuthError;

/**
 * Maps {@link AuthError} variants to Stripe-shape HTTP error responses.
 *
 * <h2>Status code conventions</h2>
 * <ul>
 *     <li>401 — credentials invalid OR no credentials (Unauthenticated,
 *         InvalidCredentials, InvalidRefreshToken, RefreshTokenReuseDetected,
 *         OldPasswordMismatch).</li>
 *     <li>403 — authenticated but lacks permission (Forbidden, AccountInactive).</li>
 *     <li>422 — input validation failed (InvalidEmail, WeakPassword).</li>
 *     <li>409 — conflict (EmailAlreadyTaken).</li>
 * </ul>
 *
 * <h2>Messages</h2>
 * Deliberately terse and uniform — credentials-related failures all
 * surface as {@code "Invalid credentials"} (no distinction between
 * "user not found" and "wrong password"). Helps defeat user-enumeration
 * via response bodies as well as timing.
 */
public final class AuthErrorMapper {

    private AuthErrorMapper() {}

    public static HttpResult toHttpResult(AuthError error) {
        return switch (error) {
            case AuthError.InvalidCredentials _ -> result(401,
                "invalid_credentials", "Invalid credentials");
            case AuthError.InvalidRefreshToken _ -> result(401,
                "invalid_refresh_token", "Invalid or expired refresh token");
            case AuthError.RefreshTokenReuseDetected _ -> result(401,
                "refresh_token_reuse_detected",
                "Refresh token reuse detected; all sessions revoked. Please re-authenticate.");
            case AuthError.OldPasswordMismatch _ -> result(401,
                "old_password_mismatch", "Current password is incorrect");
            case AuthError.Unauthenticated _ -> result(401,
                "unauthenticated", "Authentication required");
            case AuthError.AccountInactive _ -> result(403,
                "account_inactive", "Account is not active");
            case AuthError.Forbidden f -> result(403,
                "forbidden",
                "Required permission: " + f.required().name());
            case AuthError.EmailAlreadyTaken e -> result(409,
                "email_already_taken", "Email already in use: " + e.email().value());
            case AuthError.InvalidEmail e -> result(422,
                "invalid_email", "Invalid email '" + e.input() + "': " + e.reason());
            case AuthError.WeakPassword w -> result(422,
                "weak_password", "Password does not meet requirements: " + w.reason());
        };
    }

    private static HttpResult result(int status, String code, String message) {
        return new HttpResult(status, ErrorResponse.of(code, message));
    }
}
