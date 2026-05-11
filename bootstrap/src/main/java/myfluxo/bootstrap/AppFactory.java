package myfluxo.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import io.avaje.inject.Bean;
import io.avaje.inject.Factory;
import myfluxo.adapter.persistence.jdbc.JdbcDataSourceFactory;
import myfluxo.adapter.persistence.jdbc.JdbiSetup;
import myfluxo.adapter.persistence.jdbc.outbox.EntityArchiveSink;
import myfluxo.adapter.persistence.jdbc.outbox.JdbiOutboxDispatcher;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.function.BiConsumer;

/**
 * Beans the DI container can't construct on its own:
 * <ul>
 *     <li>{@link Clock} — environment dependency (just {@code systemUTC()}
 *         here; in tests you can override with a fixed clock).</li>
 *     <li>{@link DataSource} + {@link Jdbi} — built from env vars; the
 *         single persistence backend.</li>
 *     <li>{@link JdbiOutboxDispatcher} — needs the Jdbi instance plus a
 *         sink (where the dispatched events should ultimately go).</li>
 * </ul>
 *
 * <p>Every other class is wired via {@code @Singleton} and constructor
 * injection. There is only one persistence backend (Postgres via JDBI) —
 * no profile-switching, no in-memory fallback.
 */
@Factory
public final class AppFactory {

    private static final Logger LOG = LoggerFactory.getLogger(AppFactory.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Production data source from env vars; Flyway migrations run at startup.
     * Closed via {@code @Bean(destroyMethod = "close")} when the BeanScope shuts down.
     */
    @Bean(destroyMethod = "close")
    DataSource dataSource() {
        var jdbcUrl = required("MYFLUXO_JDBC_URL");
        var username = required("MYFLUXO_DB_USER");
        var password = required("MYFLUXO_DB_PASSWORD");
        var config = JdbcDataSourceFactory.Config.defaults(jdbcUrl, username, password);
        var ds = JdbcDataSourceFactory.build(config);
        JdbcDataSourceFactory.migrate(ds, config);
        return ds;
    }

    /**
     * Central Jdbi instance, configured with Postgres + SqlObject plugins
     * and the domain row mappers. Thread-safe and shared for the life of
     * the application.
     */
    @Bean
    Jdbi jdbi(DataSource dataSource) {
        return JdbiSetup.build(dataSource);
    }

    /**
     * Outbox dispatcher wired against the Jdbi instance. The sink is a
     * composite: the archive sink first (catches {@code XxxEvent$Deleted}
     * events and writes their snapshot to {@code entity_archive}), then
     * the logger sink. Add more — Kafka producer, webhook dispatcher,
     * etc. — by composing them in here.
     */
    @Bean
    JdbiOutboxDispatcher outboxDispatcher(Jdbi jdbi) {
        var archiveSink = new EntityArchiveSink(jdbi);
        BiConsumer<String, JsonNode> sink = (eventType, payload) -> {
            archiveSink.accept(eventType, payload);
            LOG.info("outbox dispatched {}", eventType);
        };
        return new JdbiOutboxDispatcher(jdbi, sink);
    }

    private static String required(String envVar) {
        var v = System.getenv(envVar);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Required env var missing: " + envVar);
        }
        return v;
    }
}
