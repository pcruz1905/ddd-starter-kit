package myfluxo.adapter.http.auth;

import io.helidon.webserver.http.ServerRequest;
import jakarta.inject.Singleton;
import myfluxo.domain.auth.TokenIssuer;
import myfluxo.domain.auth.errors.AuthError;
import myfluxo.domain.auth.model.Permission;
import myfluxo.kernel.result.Result;

import java.util.Optional;

/**
 * Extracts and validates a Bearer token from the
 * {@code Authorization} header, optionally checking that the caller
 * holds a required permission.
 *
 * <h2>Usage</h2>
 *
 * <p>Authentication only:
 * <pre>{@code
 *   var auth = bearerAuth.require(req);
 *   // Result<CurrentUser, AuthError> — Err is Unauthenticated
 * }</pre>
 *
 * <p>Authentication + permission:
 * <pre>{@code
 *   var auth = bearerAuth.requirePermission(req, Permission.USERS_DELETE);
 *   // Err can be Unauthenticated (no token) or Forbidden (token but lacks perm)
 * }</pre>
 *
 * <h2>Bearer-only</h2>
 * Only the {@code Authorization: Bearer <jwt>} scheme is accepted. Other
 * schemes (Basic, ApiKey, etc.) would be a separate authentication
 * surface.
 *
 * <h2>Testability</h2>
 * The core logic operates on a plain {@code String} header value;
 * the {@code ServerRequest} overloads just extract that string and
 * delegate. Unit tests use the string variant directly so they don't
 * need to mock Helidon's request types.
 */
@Singleton
public final class JwtBearerAuth {

    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenIssuer tokenIssuer;

    public JwtBearerAuth(TokenIssuer tokenIssuer) {
        this.tokenIssuer = tokenIssuer;
    }

    // ── Authentication only ─────────────────────────────────────────────

    public Result<CurrentUser, AuthError> require(ServerRequest req) {
        return require(extractAuthorizationHeader(req));
    }

    /** Core: validate the raw {@code Authorization} header value. */
    public Result<CurrentUser, AuthError> require(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return Result.err(new AuthError.Unauthenticated());
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        var validated = tokenIssuer.validate(token);
        return validated.fold(
            claims -> Result.ok(new CurrentUser(claims.userId(), claims.role())),
            tokenError -> Result.err(new AuthError.Unauthenticated())
        );
    }

    // ── Authentication + permission ─────────────────────────────────────

    public Result<CurrentUser, AuthError> requirePermission(
        ServerRequest req, Permission permission
    ) {
        return requirePermission(extractAuthorizationHeader(req), permission);
    }

    /**
     * Core: require a valid bearer AND that the caller holds
     * {@code permission}. Two-stage failure:
     * <ul>
     *     <li>No / bad token → {@link AuthError.Unauthenticated} (401)</li>
     *     <li>Token valid but role lacks the permission → {@link AuthError.Forbidden} (403)</li>
     * </ul>
     */
    public Result<CurrentUser, AuthError> requirePermission(
        String authorizationHeader, Permission permission
    ) {
        var authResult = require(authorizationHeader);
        return authResult.flatMap(current ->
            current.hasPermission(permission)
                ? Result.<CurrentUser, AuthError>ok(current)
                : Result.<CurrentUser, AuthError>err(new AuthError.Forbidden(permission))
        );
    }

    private static String extractAuthorizationHeader(ServerRequest req) {
        Optional<String> value = req.headers().first(
            io.helidon.http.HeaderNames.create(AUTHORIZATION));
        return value.orElse(null);
    }
}
