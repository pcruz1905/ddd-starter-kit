package myfluxo.adapter.http.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.helidon.http.Status;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import jakarta.inject.Singleton;
import myfluxo.adapter.http.ErrorResponse;
import myfluxo.adapter.http.idempotency.HttpResult;
import myfluxo.application.auth.AuthSession;
import myfluxo.application.auth.commands.ChangePasswordCommand;
import myfluxo.application.auth.commands.LoginCommand;
import myfluxo.application.auth.commands.LogoutCommand;
import myfluxo.application.auth.commands.RefreshSessionCommand;
import myfluxo.application.auth.commands.RegisterCommand;
import myfluxo.application.auth.usecases.ChangePassword;
import myfluxo.application.auth.usecases.Login;
import myfluxo.application.auth.usecases.Logout;
import myfluxo.application.auth.usecases.RefreshSession;
import myfluxo.application.auth.usecases.Register;
import myfluxo.domain.auth.errors.AuthError;
import myfluxo.domain.auth.model.RefreshTokenId;
import myfluxo.domain.users.model.UserId;
import myfluxo.kernel.result.Result;

/**
 * Auth bounded-context HTTP surface.
 *
 * <pre>
 * POST /v1/auth/register         {email, password} → 201 {userId, role, accessToken, ...}
 * POST /v1/auth/login            {email, password} → 200 {userId, role, accessToken, ...}
 * POST /v1/auth/refresh          {refreshToken}    → 200 {accessToken, refreshToken, ...}
 * POST /v1/auth/logout           {refreshToken}    → 204 (Bearer optional but recommended)
 * POST /v1/auth/change-password  Bearer + {oldPassword, newPassword} → 204
 * </pre>
 *
 * <p>Bodies are JSON. Errors come back in the Stripe-style envelope
 * defined by {@link ErrorResponse}, mapped from {@link AuthError} via
 * {@link AuthErrorMapper}.
 */
@Singleton
public final class AuthRoutes implements HttpService {

    private final Register register;
    private final Login login;
    private final RefreshSession refreshSession;
    private final Logout logout;
    private final ChangePassword changePassword;
    private final JwtBearerAuth bearerAuth;
    private final AuthRateLimiter rateLimiter;
    private final ObjectMapper json;

    public AuthRoutes(
        Register register,
        Login login,
        RefreshSession refreshSession,
        Logout logout,
        ChangePassword changePassword,
        JwtBearerAuth bearerAuth,
        AuthRateLimiter rateLimiter,
        ObjectMapper json
    ) {
        this.register = register;
        this.login = login;
        this.refreshSession = refreshSession;
        this.logout = logout;
        this.changePassword = changePassword;
        this.bearerAuth = bearerAuth;
        this.rateLimiter = rateLimiter;
        this.json = json;
    }

    @Override
    public void routing(HttpRules rules) {
        rules
            .post("/v1/auth/register", this::handleRegister)
            .post("/v1/auth/login", this::handleLogin)
            .post("/v1/auth/refresh", this::handleRefresh)
            .post("/v1/auth/logout", this::handleLogout)
            .post("/v1/auth/change-password", this::handleChangePassword)
            .get("/v1/auth/me", this::handleMe);
    }

    // ── Public endpoints ────────────────────────────────────────────────

    private void handleRegister(ServerRequest req, ServerResponse res) {
        if (!rateLimiter.allowRegister(callerIp(req))) {
            send(res, rateLimitExceeded());
            return;
        }
        var parsed = parseBody(req, RegisterRequest.class);
        if (parsed instanceof Result.Err<RegisterRequest, HttpResult>(HttpResult err)) {
            send(res, err);
            return;
        }
        var body = ((Result.Ok<RegisterRequest, HttpResult>) parsed).value();
        var result = register.handle(new RegisterCommand(body.email(), body.password()));

        switch (result) {
            case Result.Ok<AuthSession, AuthError>(AuthSession s) ->
                send(res, new HttpResult(Status.CREATED_201.code(), AuthSessionResponse.from(s)));
            case Result.Err<AuthSession, AuthError>(AuthError e) ->
                send(res, AuthErrorMapper.toHttpResult(e));
        }
    }

    private void handleLogin(ServerRequest req, ServerResponse res) {
        if (!rateLimiter.allowLogin(callerIp(req))) {
            send(res, rateLimitExceeded());
            return;
        }
        var parsed = parseBody(req, LoginRequest.class);
        if (parsed instanceof Result.Err<LoginRequest, HttpResult>(HttpResult err)) {
            send(res, err);
            return;
        }
        var body = ((Result.Ok<LoginRequest, HttpResult>) parsed).value();
        var result = login.handle(new LoginCommand(body.email(), body.password()));

        switch (result) {
            case Result.Ok<AuthSession, AuthError>(AuthSession s) ->
                send(res, new HttpResult(Status.OK_200.code(), AuthSessionResponse.from(s)));
            case Result.Err<AuthSession, AuthError>(AuthError e) ->
                send(res, AuthErrorMapper.toHttpResult(e));
        }
    }

    private void handleRefresh(ServerRequest req, ServerResponse res) {
        var parsed = parseBody(req, RefreshRequest.class);
        if (parsed instanceof Result.Err<RefreshRequest, HttpResult>(HttpResult err)) {
            send(res, err);
            return;
        }
        var body = ((Result.Ok<RefreshRequest, HttpResult>) parsed).value();
        var result = refreshSession.handle(new RefreshSessionCommand(body.refreshToken()));

        switch (result) {
            case Result.Ok<AuthSession, AuthError>(AuthSession s) ->
                send(res, new HttpResult(Status.OK_200.code(), AuthSessionResponse.from(s)));
            case Result.Err<AuthSession, AuthError>(AuthError e) ->
                send(res, AuthErrorMapper.toHttpResult(e));
        }
    }

    private void handleLogout(ServerRequest req, ServerResponse res) {
        var parsed = parseBody(req, LogoutRequest.class);
        if (parsed instanceof Result.Err<LogoutRequest, HttpResult>(HttpResult err)) {
            send(res, err);
            return;
        }
        var body = ((Result.Ok<LogoutRequest, HttpResult>) parsed).value();
        var result = logout.handle(new LogoutCommand(body.refreshToken()));

        switch (result) {
            case Result.Ok<RefreshTokenId, AuthError> ignored -> {
                res.status(Status.NO_CONTENT_204);
                res.send();
            }
            case Result.Err<RefreshTokenId, AuthError>(AuthError e) ->
                send(res, AuthErrorMapper.toHttpResult(e));
        }
    }

    // ── Authenticated endpoints ─────────────────────────────────────────

    private void handleChangePassword(ServerRequest req, ServerResponse res) {
        var auth = bearerAuth.require(req);
        if (auth instanceof Result.Err<CurrentUser, AuthError>(AuthError e)) {
            send(res, AuthErrorMapper.toHttpResult(e));
            return;
        }
        var current = ((Result.Ok<CurrentUser, AuthError>) auth).value();

        var parsed = parseBody(req, ChangePasswordRequest.class);
        if (parsed instanceof Result.Err<ChangePasswordRequest, HttpResult>(HttpResult err)) {
            send(res, err);
            return;
        }
        var body = ((Result.Ok<ChangePasswordRequest, HttpResult>) parsed).value();

        var result = changePassword.handle(new ChangePasswordCommand(
            current.userId(), body.oldPassword(), body.newPassword()
        ));

        switch (result) {
            case Result.Ok<UserId, AuthError> ignored -> {
                res.status(Status.NO_CONTENT_204);
                res.send();
            }
            case Result.Err<UserId, AuthError>(AuthError e) ->
                send(res, AuthErrorMapper.toHttpResult(e));
        }
    }

    /**
     * {@code GET /v1/auth/me} — return the authenticated caller's
     * identity and role. The canonical "who am I" endpoint.
     *
     * <p>Requires a valid Bearer token (any role). For a route that
     * requires a SPECIFIC permission, use
     * {@code bearerAuth.requirePermission(req, Permission.X)} instead:
     * <pre>{@code
     *   var auth = bearerAuth.requirePermission(req, Permission.USERS_DELETE);
     *   if (auth instanceof Result.Err<CurrentUser, AuthError>(AuthError e)) {
     *       send(res, AuthErrorMapper.toHttpResult(e));
     *       return;
     *   }
     *   // ... proceed
     * }</pre>
     */
    private void handleMe(ServerRequest req, ServerResponse res) {
        var auth = bearerAuth.require(req);
        if (auth instanceof Result.Err<CurrentUser, AuthError>(AuthError e)) {
            send(res, AuthErrorMapper.toHttpResult(e));
            return;
        }
        var current = ((Result.Ok<CurrentUser, AuthError>) auth).value();
        send(res, new HttpResult(Status.OK_200.code(), CurrentUserResponse.from(current)));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private <T> Result<T, HttpResult> parseBody(ServerRequest req, Class<T> type) {
        try {
            byte[] body = req.content().as(byte[].class);
            return Result.ok(json.readValue(body, type));
        } catch (Exception ex) {
            return Result.err(new HttpResult(
                Status.BAD_REQUEST_400.code(),
                ErrorResponse.of("invalid_json", "Request body is not valid JSON")
            ));
        }
    }

    private void send(ServerResponse res, HttpResult result) {
        res.status(Status.create(result.status()));
        if (result.body() == null) {
            res.send();
        } else {
            res.send(result.body());
        }
    }

    private static String callerIp(ServerRequest req) {
        // Direct peer address. Production behind a reverse proxy should
        // parse {@code X-Forwarded-For} (trusting only the proxy chain).
        // For starter-kit purposes we use the raw connection IP — strict
        // and predictable; misconfigured proxies bucket every caller
        // under the LB's IP, which is harmless except for false-positive
        // rate-limit shared-bucket behaviour.
        try {
            return req.remotePeer().address().toString();
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static HttpResult rateLimitExceeded() {
        return new HttpResult(
            Status.TOO_MANY_REQUESTS_429.code(),
            ErrorResponse.of(
                "rate_limited",
                "Too many attempts from this IP. Try again later."
            )
        );
    }
}
