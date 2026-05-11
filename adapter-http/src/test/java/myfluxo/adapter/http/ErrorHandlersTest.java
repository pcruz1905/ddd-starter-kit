package myfluxo.adapter.http;

import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import myfluxo.kernel.aggregate.OptimisticConcurrencyException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the airlock — every uncaught exception is translated to a
 * sanitized {@link ErrorResponse}; no stack traces, no internal class
 * names, no exception messages other than what we explicitly allow.
 */
class ErrorHandlersTest {

    private HttpServer server;
    private HttpClient client;
    private String base;

    /** A test-only HttpService whose routes deliberately blow up. */
    static final class ExplodingRoutes implements HttpService {
        @Override
        public void routing(HttpRules rules) {
            rules.get("/boom/npe", (req, res) -> {
                String s = null;
                s.length(); // NullPointerException
            });
            rules.get("/boom/runtime", (req, res) -> {
                throw new RuntimeException("internal secret: db_url=jdbc:postgresql://prod:5432/secret");
            });
            rules.get("/boom/db", (req, res) -> {
                // Simulate a SQLException-shaped message (real ones contain credentials, schema names, etc).
                throw new RuntimeException("ERROR: relation \"internal_audit_log\" does not exist [42P01]");
            });
            rules.get("/boom/concurrency", (req, res) -> {
                throw new OptimisticConcurrencyException("internal-aggregate-id-xyz", 42L);
            });
            rules.get("/boom/bad-arg", (req, res) -> {
                throw new IllegalArgumentException("UserId is not a valid UUID: 'not-a-uuid'");
            });
        }
    }

    @BeforeEach
    void start() {
        server = HttpServer.start(0, new ExplodingRoutes());
        base = "http://localhost:" + server.port();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stop() { server.close(); }

    @Test
    void uncaughtNpe_returnsSanitized500WithCorrelationId() throws Exception {
        var res = get("/boom/npe");

        assertThat(res.statusCode()).isEqualTo(500);
        assertThat(res.body()).contains("\"code\":\"INTERNAL_ERROR\"");
        assertThat(res.body()).contains("\"correlationId\":");
        // Critical: we MUST NOT leak the exception type or stack trace.
        assertThat(res.body())
            .as("NullPointerException class name must not leak to the client")
            .doesNotContain("NullPointerException")
            .doesNotContain("java.lang")
            .doesNotContain("at myfluxo");
    }

    @Test
    void runtimeExceptionWithSecretMessage_doesNotLeakMessage() throws Exception {
        var res = get("/boom/runtime");

        assertThat(res.statusCode()).isEqualTo(500);
        assertThat(res.body()).contains("\"code\":\"INTERNAL_ERROR\"");
        // The whole point of the airlock: secrets in messages don't escape.
        assertThat(res.body())
            .as("Exception message containing a JDBC URL must not appear in the response")
            .doesNotContain("jdbc:postgresql")
            .doesNotContain("db_url")
            .doesNotContain("secret");
    }

    @Test
    void simulatedDbError_doesNotLeakSchemaDetails() throws Exception {
        var res = get("/boom/db");

        assertThat(res.statusCode()).isEqualTo(500);
        assertThat(res.body())
            .as("SQL error details must not appear in the response")
            .doesNotContain("internal_audit_log")
            .doesNotContain("42P01")
            // "relation" as a whole word would be a Postgres error message
            // leak — but it appears as a substring of the legitimate
            // `correlationId` field, so match it bounded.
            .doesNotContain(" relation ")
            .doesNotContain("\"relation\"")
            .doesNotContain("relation \"");
    }

    @Test
    void optimisticConcurrency_returns409WithoutLeakingVersionOrId() throws Exception {
        var res = get("/boom/concurrency");

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("\"code\":\"OPTIMISTIC_CONCURRENCY\"");
        // Internal aggregate id and version are NOT in the message.
        assertThat(res.body())
            .doesNotContain("internal-aggregate-id-xyz")
            .doesNotContain("42");
    }

    @Test
    void illegalArgument_returns400WithMessageVisible() throws Exception {
        var res = get("/boom/bad-arg");

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("\"code\":\"BAD_REQUEST\"");
        // For IllegalArgumentException we DO pass the message — it comes
        // from designed-to-be-seen compact constructor checks.
        assertThat(res.body()).contains("valid UUID");
    }

    private HttpResponse<String> get(String path) throws Exception {
        var req = HttpRequest.newBuilder().uri(URI.create(base + path)).GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
