package myfluxo.adapter.persistence.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

/**
 * Builds a production-grade Postgres {@link DataSource} backed by HikariCP,
 * and runs Flyway migrations against it.
 *
 * <p>Defaults are picked for a typical web SaaS workload (max 20 connections,
 * 30s connect timeout, 30min max lifetime). Tune via the {@link Config}
 * record at the boundary — the defaults are not magic numbers for forever.
 */
public final class JdbcDataSourceFactory {

    private JdbcDataSourceFactory() {}

    public record Config(
        String jdbcUrl,
        String username,
        String password,
        int maximumPoolSize,
        int minimumIdle,
        long connectionTimeoutMs,
        long idleTimeoutMs,
        long maxLifetimeMs,
        String migrationLocation
    ) {
        public static Config defaults(String jdbcUrl, String username, String password) {
            return new Config(
                jdbcUrl, username, password,
                /* maximumPoolSize     */ 20,
                /* minimumIdle         */ 5,
                /* connectionTimeoutMs */ 30_000L,
                /* idleTimeoutMs       */ 600_000L,
                /* maxLifetimeMs       */ 1_800_000L,
                /* migrationLocation   */ "classpath:db/migration"
            );
        }
    }

    /**
     * Build a configured, started DataSource. Caller owns the lifecycle —
     * close it on shutdown.
     */
    public static HikariDataSource build(Config config) {
        var hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(config.maximumPoolSize());
        hikari.setMinimumIdle(config.minimumIdle());
        hikari.setConnectionTimeout(config.connectionTimeoutMs());
        hikari.setIdleTimeout(config.idleTimeoutMs());
        hikari.setMaxLifetime(config.maxLifetimeMs());
        // Pool default is autoCommit=true; JDBI toggles it during
        // explicit transactions (Handle#begin/commit/rollback). With
        // autoCommit=false at the pool, non-transactional withHandle /
        // useHandle calls would silently roll back when the connection
        // returns to the pool.
        hikari.setAutoCommit(true);
        hikari.setPoolName("myfluxo-pg");
        return new HikariDataSource(hikari);
    }

    /**
     * Run pending Flyway migrations against the given DataSource. Call this
     * once at startup, after building the DataSource and before serving any
     * traffic.
     */
    public static void migrate(DataSource dataSource, Config config) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations(config.migrationLocation())
            .load()
            .migrate();
    }
}
