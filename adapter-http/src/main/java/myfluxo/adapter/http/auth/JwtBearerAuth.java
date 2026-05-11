package myfluxo.adapter.http.auth;

import io.helidon.webserver.http.ServerRequest;
import jakarta.inject.Singleton;
import myfluxo.domain.auth.TokenIssuer;
import myfluxo.domain.auth.errors.AuthError;
import myfluxo.kernel.result.Result;

import java.util.Optional;

/**
 * Extracts and validates a Bearer token from the
 * {@code Authorization} header of an HTTP request.
 *
 * <p>Used by route handlers that require authentication. Pattern:
 * <pre>{@code
 *   var authResult = bearerAuth.require(req);
 *   if (authResult.isErr()) {
 *       return new HttpResult(401, AuthErrorMapper.toResponse(authResult.unwrapErr()));
 *   }
 *   var current = authResult.orElseThrow();
 *   // ... use current.userId() in commands
 * }</pre>
 *
 * <h2>Bearer-only</h2>
 * Only the {@code Authorization: Bearer <jwt>} scheme is accepted. Other
 * schemes (Basic, ApiKey, etc.) are not parsed — that's a separate
 * authentication surface if/when needed.
 */
@Singleton
public final class JwtBearerAuth {

    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenIssuer tokenIssuer;

    public JwtBearerAuth(TokenIssuer tokenIssuer) {
        this.tokenIssuer = tokenIssuer;
    }

    /**
     * Try to extract a current user from the request. Returns
     * {@link AuthError.Unauthenticated} if no Bearer header is present
     * or the token is malformed/expired/etc. Maps every
     * {@link TokenIssuer.TokenError} variant to
     * {@link AuthError.Unauthenticated} — the route doesn't need finer
     * detail for HTTP-level rejection.
     */
    public Result<CurrentUser, AuthError> require(ServerRequest req) {
        Optional<String> headerValue = req.headers().first(
            io.helidon.http.HeaderNames.create(AUTHORIZATION));

        if (headerValue.isEmpty() || !headerValue.get().startsWith(BEARER_PREFIX)) {
            return Result.err(new AuthError.Unauthenticated());
        }

        String token = headerValue.get().substring(BEARER_PREFIX.length()).trim();
        var validated = tokenIssuer.validate(token);

        return validated.fold(
            claims -> Result.ok(new CurrentUser(claims.userId(), claims.role())),
            tokenError -> Result.err(new AuthError.Unauthenticated())
        );
    }
}
