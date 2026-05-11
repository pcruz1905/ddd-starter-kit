package myfluxo.adapter.http.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import myfluxo.adapter.auth.Argon2PasswordHasher;
import myfluxo.adapter.auth.HmacRefreshTokenStrategy;
import myfluxo.adapter.auth.JwtTokenIssuer;
import myfluxo.adapter.http.HttpServer;
import myfluxo.adapter.persistence.jdbc.JdbiUnitOfWork;
import myfluxo.adapter.persistence.jdbc.PostgresContainerSupport;
import myfluxo.adapter.persistence.jdbc.auth.JdbiCredentialsRepository;
import myfluxo.adapter.persistence.jdbc.auth.JdbiRefreshTokenRepository;
import myfluxo.adapter.persistence.jdbc.outbox.JdbiOutboxDomainEventPublisher;
import myfluxo.adapter.persistence.jdbc.users.JdbiUserRepository;
import myfluxo.application.auth.AuthAuditLogger;
import myfluxo.application.auth.RefreshTokenTtl;
import myfluxo.application.auth.usecases.ChangePassword;
import myfluxo.application.auth.usecases.Login;
import myfluxo.application.auth.usecases.Logout;
import myfluxo.application.auth.usecases.RefreshSession;
import myfluxo.application.auth.usecases.Register;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack auth IT. Boots the HTTP server with the production
 * impls — real Argon2id hasher, real HS256 JWT issuer, real HMAC
 * refresh-token strategy, real Postgres-backed repositories — and
 * exercises the canonical user journey plus the theft-detection edge.
 */
class AuthRoutesIT {

    // Fixed-length, deterministic secrets for the test fixture.
    private static final byte[] JWT_SECRET = "test-jwt-secret-32-bytes-padding!".getBytes();
    private static final byte[] REFRESH_SECRET = "test-refresh-secret-32-bytes-pad!".getBytes();

    private Jdbi jdbi;
    private HttpServer server;
    private HttpClient client;
    private ObjectMapper json;
    private String base;

    @BeforeEach
    void start() {
        jdbi = PostgresContainerSupport.jdbi();
        var uow = new JdbiUnitOfWork(jdbi);
        var clock = Clock.systemUTC();

        var users = new JdbiUserRepository(uow);
        var credentials = new JdbiCredentialsRepository(uow);
        var tokens = new JdbiRefreshTokenRepository(uow);
        var events = new JdbiOutboxDomainEventPublisher(uow);
        var hasher = new Argon2PasswordHasher();
        var tokenIssuer = new JwtTokenIssuer(
            JWT_SECRET, "myfluxo", Duration.ofMinutes(15), clock);
        var refreshStrategy = new HmacRefreshTokenStrategy(REFRESH_SECRET);
        var audit = new AuthAuditLogger();
        var refreshTtl = RefreshTokenTtl.of(Duration.ofDays(7));

        var register = new Register(
            users, credentials, tokens,
            hasher, tokenIssuer, refreshStrategy, events, audit,
            uow, clock, refreshTtl);
        var login = new Login(
            users, credentials, tokens,
            hasher, tokenIssuer, refreshStrategy, audit,
            uow, clock, refreshTtl);
        var refresh = new RefreshSession(
            tokens, users, refreshStrategy, tokenIssuer, audit,
            uow, clock, refreshTtl);
        var logout = new Logout(tokens, refreshStrategy, audit, uow, clock);
        var changePassword = new ChangePassword(
            credentials, tokens, hasher, audit, uow, clock);

        json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        var bearerAuth = new JwtBearerAuth(tokenIssuer);
        var rateLimiter = new AuthRateLimiter();
        var routes = new AuthRoutes(
            register, login, refresh, logout, changePassword,
            bearerAuth, rateLimiter, json);

        jdbi.useHandle(h -> {
            h.execute("DELETE FROM refresh_tokens");
            h.execute("DELETE FROM credentials");
            h.execute("DELETE FROM entity_archive");
            h.execute("DELETE FROM outbox_events");
            h.execute("DELETE FROM users");
        });

        server = HttpServer.start(0, routes);
        base = "http://localhost:" + server.port();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    // ── Happy path ──────────────────────────────────────────────────────

    @Test
    void fullFlow_register_me_refresh_logout() throws Exception {
        // 1. Register.
        var regRes = post("/v1/auth/register", """
            { "email": "alice@example.com", "password": "secretpw-1234" }
            """, null);
        assertThat(regRes.statusCode()).isEqualTo(201);
        var regBody = json.readTree(regRes.body());
        String accessToken = regBody.get("accessToken").asText();
        String refreshToken = regBody.get("refreshToken").asText();
        assertThat(regBody.get("role").asText()).isEqualTo("MEMBER");

        // 2. /me with the access token.
        var meRes = get("/v1/auth/me", accessToken);
        assertThat(meRes.statusCode()).isEqualTo(200);
        var meBody = json.readTree(meRes.body());
        assertThat(meBody.get("role").asText()).isEqualTo("MEMBER");
        assertThat(meBody.get("userId").asText())
            .isEqualTo(regBody.get("userId").asText());

        // 3. Refresh — get a new pair.
        var refreshRes = post("/v1/auth/refresh", """
            { "refreshToken": "%s" }
            """.formatted(refreshToken), null);
        assertThat(refreshRes.statusCode()).isEqualTo(200);
        var refreshBody = json.readTree(refreshRes.body());
        String newRefresh = refreshBody.get("refreshToken").asText();
        assertThat(newRefresh).isNotEqualTo(refreshToken);

        // 4. Logout with the new refresh token.
        var logoutRes = post("/v1/auth/logout", """
            { "refreshToken": "%s" }
            """.formatted(newRefresh), null);
        assertThat(logoutRes.statusCode()).isEqualTo(204);

        // 5. Using the now-revoked token → 401.
        var deadRefresh = post("/v1/auth/refresh", """
            { "refreshToken": "%s" }
            """.formatted(newRefresh), null);
        assertThat(deadRefresh.statusCode()).isEqualTo(401);
    }

    // ── Reuse detection ─────────────────────────────────────────────────

    @Test
    void reusedRotatedRefreshToken_revokesFamily_returns401() throws Exception {
        // Register → RT-1 issued.
        var regBody = json.readTree(post("/v1/auth/register", """
            { "email": "bob@example.com", "password": "secretpw-1234" }
            """, null).body());
        String rt1 = regBody.get("refreshToken").asText();

        // Refresh → RT-2 issued, RT-1 rotated.
        var rotateBody = json.readTree(post("/v1/auth/refresh", """
            { "refreshToken": "%s" }
            """.formatted(rt1), null).body());
        String rt2 = rotateBody.get("refreshToken").asText();

        // Attacker replays RT-1 → 401, family revoked.
        var replay = post("/v1/auth/refresh", """
            { "refreshToken": "%s" }
            """.formatted(rt1), null);
        assertThat(replay.statusCode()).isEqualTo(401);
        assertThat(replay.body()).contains("refresh_token_reuse_detected");

        // RT-2 (was active) is now also dead because the family was revoked.
        var legitFollowup = post("/v1/auth/refresh", """
            { "refreshToken": "%s" }
            """.formatted(rt2), null);
        assertThat(legitFollowup.statusCode()).isEqualTo(401);
    }

    // ── Login + wrong password ──────────────────────────────────────────

    @Test
    void login_wrongPassword_returns401InvalidCredentials() throws Exception {
        post("/v1/auth/register", """
            { "email": "carol@example.com", "password": "secretpw-1234" }
            """, null);

        var res = post("/v1/auth/login", """
            { "email": "carol@example.com", "password": "wrong-password" }
            """, null);

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("invalid_credentials");
    }

    @Test
    void me_withoutBearer_returns401() throws Exception {
        var res = get("/v1/auth/me", null);
        assertThat(res.statusCode()).isEqualTo(401);
    }

    // ── Rate limiting ───────────────────────────────────────────────────

    @Test
    void login_burst_eventuallyRateLimited() throws Exception {
        // The rate limiter is 5 login attempts per 15 min. We blast
        // 10 attempts at the same IP (this process). At least one
        // should return 429.
        boolean sawRateLimit = false;
        for (int i = 0; i < 10; i++) {
            var res = post("/v1/auth/login", """
                { "email": "rate-limit-test@example.com", "password": "anything-12345" }
                """, null);
            if (res.statusCode() == 429) {
                sawRateLimit = true;
                assertThat(res.body()).contains("rate_limited");
                break;
            }
        }
        assertThat(sawRateLimit)
            .as("Login bucket should refuse after 5 attempts in 15 minutes")
            .isTrue();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        var b = HttpRequest.newBuilder()
            .uri(URI.create(base + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        var b = HttpRequest.newBuilder()
            .uri(URI.create(base + path))
            .GET();
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
