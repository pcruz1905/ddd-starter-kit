package myfluxo.adapter.http.users;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import myfluxo.adapter.http.HttpServer;
import myfluxo.adapter.http.idempotency.IdempotencyMiddleware;
import myfluxo.adapter.persistence.jdbc.JdbiIdempotencyCache;
import myfluxo.adapter.persistence.jdbc.JdbiUnitOfWork;
import myfluxo.adapter.persistence.jdbc.PostgresContainerSupport;
import myfluxo.adapter.persistence.jdbc.outbox.JdbiOutboxDomainEventPublisher;
import myfluxo.adapter.persistence.jdbc.users.JdbiUserRepository;
import myfluxo.application.users.usecases.RegisterUser;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end HTTP integration test. Boots the HTTP server with the
 * production stack — real {@link JdbiUserRepository}, real outbox,
 * real Postgres, and the real {@link IdempotencyMiddleware} backed by
 * {@link JdbiIdempotencyCache}.
 *
 * <p>Idempotency assertions live here (not on the use case) — that's the
 * design: idempotency is an HTTP-layer concern.
 */
class UserRoutesIT {

    private Jdbi jdbi;
    private HttpServer server;
    private HttpClient client;
    private String base;

    @BeforeEach
    void start() {
        jdbi = PostgresContainerSupport.jdbi();
        var uow = new JdbiUnitOfWork(jdbi);
        var repo = new JdbiUserRepository(uow);
        var events = new JdbiOutboxDomainEventPublisher(uow);
        var registerUser = new RegisterUser(repo, uow, events, Clock.systemUTC());
        var cache = new JdbiIdempotencyCache(jdbi);
        var json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var middleware = new IdempotencyMiddleware(cache, json);
        var routes = new UserRoutes(registerUser, repo, middleware, json);

        jdbi.useHandle(h -> {
            h.execute("DELETE FROM entity_archive");
            h.execute("DELETE FROM outbox_events");
            h.execute("DELETE FROM users");
            h.execute("DELETE FROM idempotency_cache");
        });

        server = HttpServer.start(0, routes);
        base = "http://localhost:" + server.port();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void registerUser_returns201WithBody() throws Exception {
        var res = post("/users", """
            { "email": "alice@example.com" }
            """, null);

        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(res.body()).contains("\"email\":\"alice@example.com\"");
        assertThat(res.body()).contains("\"status\":\"pending\"");
    }

    @Test
    void registerUser_duplicate_returns409EmailAlreadyTaken() throws Exception {
        post("/users", "{ \"email\": \"alice@example.com\" }", null);

        var res = post("/users", "{ \"email\": \"alice@example.com\" }", null);

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("EMAIL_ALREADY_TAKEN");
    }

    @Test
    void registerUser_invalidEmail_returns400InvalidEmail() throws Exception {
        var res = post("/users", "{ \"email\": \"not-an-email\" }", null);

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("INVALID_EMAIL");
    }

    @Test
    void retryWithSameIdempotencyKey_replaysCachedResponseAndSetsReplayHeader() throws Exception {
        var first = post("/users", "{ \"email\": \"bob@example.com\" }", "client-key-1");
        var second = post("/users", "{ \"email\": \"bob@example.com\" }", "client-key-1");

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode())
            .as("retry with the same Idempotency-Key returns the cached 201, not a 409")
            .isEqualTo(201);
        assertThat(extractId(first.body())).isEqualTo(extractId(second.body()));

        assertThat(second.headers().firstValue("X-Idempotent-Replay"))
            .as("replayed responses are flagged via X-Idempotent-Replay")
            .contains("true");
        assertThat(first.headers().firstValue("X-Idempotent-Replay"))
            .as("first response is not a replay")
            .isEmpty();
    }

    @Test
    void sameKeyWithDifferentBody_returns422() throws Exception {
        var first = post("/users", "{ \"email\": \"carol@example.com\" }", "client-key-2");
        var second = post("/users", "{ \"email\": \"dave@example.com\" }", "client-key-2");

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode())
            .as("reusing an idempotency key with a different body is a client bug — 422")
            .isEqualTo(422);
        assertThat(second.body()).contains("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_BODY");
    }

    @Test
    void differentKeys_areIndependent() throws Exception {
        var a = post("/users", "{ \"email\": \"eve@example.com\" }", "client-key-A");
        var b = post("/users", "{ \"email\": \"frank@example.com\" }", "client-key-B");

        assertThat(a.statusCode()).isEqualTo(201);
        assertThat(b.statusCode()).isEqualTo(201);
        assertThat(extractId(a.body())).isNotEqualTo(extractId(b.body()));
    }

    @Test
    void findById_unknownUuid_returns404() throws Exception {
        var res = get("/users/00000000-0000-0000-0000-000000000000");
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body()).contains("USER_NOT_FOUND");
    }

    @Test
    void findById_malformedUuid_returns400() throws Exception {
        var res = get("/users/not-a-uuid");
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("INVALID_USER_ID");
    }

    private HttpResponse<String> post(String path, String json, String idempotencyKey) throws Exception {
        var builder = HttpRequest.newBuilder()
            .uri(URI.create(base + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json));
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        var req = HttpRequest.newBuilder().uri(URI.create(base + path)).GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String extractId(String json) {
        int idx = json.indexOf("\"id\":\"");
        if (idx < 0) return null;
        int start = idx + 6;
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }
}
