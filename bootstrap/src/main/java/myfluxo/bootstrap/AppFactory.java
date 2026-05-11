package myfluxo.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import io.avaje.inject.Bean;
import io.avaje.inject.Factory;
import myfluxo.adapter.auth.HmacRefreshTokenStrategy;
import myfluxo.adapter.auth.JwtTokenIssuer;
import myfluxo.adapter.persistence.jdbc.JdbcDataSourceFactory;
import myfluxo.adapter.persistence.jdbc.JdbiSetup;
import myfluxo.adapter.persistence.jdbc.outbox.EntityArchiveSink;
import myfluxo.adapter.persistence.jdbc.outbox.JdbiOutboxDispatcher;
import myfluxo.application.auth.AccessTokenTtl;
import myfluxo.application.auth.RefreshTokenTtl;
import myfluxo.domain.auth.RefreshTokenStrategy;
import myfluxo.domain.auth.TokenIssuer;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
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

    // ── Auth wiring ─────────────────────────────────────────────────────

    /**
     * Access-token lifetime. Override via {@code MYFLUXO_ACCESS_TOKEN_TTL_MINUTES}.
     * Default 15 minutes — short-lived to bound the exposure window of a
     * stolen JWT.
     */
    @Bean
    AccessTokenTtl accessTokenTtl() {
        long minutes = parseLongEnv("MYFLUXO_ACCESS_TOKEN_TTL_MINUTES", 15L);
        return AccessTokenTtl.of(Duration.ofMinutes(minutes));
    }

    /**
     * Refresh-token lifetime. Override via {@code MYFLUXO_REFRESH_TOKEN_TTL_DAYS}.
     * Default 7 days.
     */
    @Bean
    RefreshTokenTtl refreshTokenTtl() {
        long days = parseLongEnv("MYFLUXO_REFRESH_TOKEN_TTL_DAYS", 7L);
        return RefreshTokenTtl.of(Duration.ofDays(days));
    }

    /**
     * JWT issuer/validator for access tokens. Signed with HMAC-SHA256
     * using {@code MYFLUXO_JWT_SECRET} (>= 32 bytes, UTF-8). Issuer
     * claim defaults to {@code "myfluxo"}; override with
     * {@code MYFLUXO_JWT_ISSUER}.
     */
    @Bean
    TokenIssuer tokenIssuer(Clock clock, AccessTokenTtl accessTokenTtl) {
        byte[] secret = readSecret("MYFLUXO_JWT_SECRET");
        String issuer = System.getenv().getOrDefault("MYFLUXO_JWT_ISSUER", "myfluxo");
        return new JwtTokenIssuer(secret, issuer, accessTokenTtl.value(), clock);
    }

    /**
     * HMAC-SHA256 refresh-token strategy. Secret from
     * {@code MYFLUXO_REFRESH_TOKEN_SECRET} (>= 32 bytes, UTF-8). Distinct
     * from the JWT secret so a compromise of one doesn't compromise both.
     */
    @Bean
    RefreshTokenStrategy refreshTokenStrategy() {
        byte[] secret = readSecret("MYFLUXO_REFRESH_TOKEN_SECRET");
        return new HmacRefreshTokenStrategy(secret);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static String required(String envVar) {
        var v = System.getenv(envVar);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Required env var missing: " + envVar);
        }
        return v;
    }

    /**
     * Read an env-var-supplied secret as UTF-8 bytes; reject anything
     * shorter than 256 bits. Suggested generation: {@code openssl rand -hex 32}.
     */
    private static byte[] readSecret(String envVar) {
        byte[] bytes = required(envVar).getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                envVar + " must be at least 32 bytes (256 bits); got "
                + bytes.length + ". Generate via `openssl rand -hex 32`.");
        }
        return bytes;
    }

    private static long parseLongEnv(String envVar, long fallback) {
        var v = System.getenv(envVar);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException(
                envVar + " is not a valid integer: " + v);
        }
    }
}
