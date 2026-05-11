package myfluxo.bootstrap;

import io.avaje.inject.BeanScope;
import myfluxo.adapter.http.HttpServer;
import myfluxo.adapter.http.users.UserRoutes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;

/**
 * Composition root.
 *
 * <p>Avaje Inject builds the bean graph at compile time. At startup, the
 * required env vars ({@code MYFLUXO_JDBC_URL}, {@code MYFLUXO_DB_USER},
 * {@code MYFLUXO_DB_PASSWORD}) must be set — there is no in-memory
 * fallback; production-realism is enforced even locally via the
 * Postgres adapter. For tests use Testcontainers + {@code PostgresContainerSupport}.
 *
 * <p>Every other class is wired via {@code @Singleton} / {@code @Factory}
 * annotations and constructor injection.
 */
public final class Application {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) throws InterruptedException {
        try (BeanScope scope = BeanScope.builder().build()) {

            var routes = scope.get(UserRoutes.class);
            var port = parsePort(System.getenv("MYFLUXO_HTTP_PORT"), 8080);

            try (var server = HttpServer.start(port, routes)) {
                LOG.info("listening on http://localhost:{}", server.port());
                var latch = new CountDownLatch(1);
                Runtime.getRuntime().addShutdownHook(new Thread(latch::countDown));
                latch.await();
                LOG.info("shutting down");
            }
        }
    }

    private static int parsePort(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("MYFLUXO_HTTP_PORT is not a valid int: " + raw);
        }
    }
}
