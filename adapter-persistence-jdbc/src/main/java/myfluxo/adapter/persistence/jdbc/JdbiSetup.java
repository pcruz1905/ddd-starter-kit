package myfluxo.adapter.persistence.jdbc;

import myfluxo.adapter.persistence.jdbc.auth.CredentialsRow;
import myfluxo.adapter.persistence.jdbc.auth.RefreshTokenRow;
import myfluxo.adapter.persistence.jdbc.process.ProcessInstanceRow;
import myfluxo.adapter.persistence.jdbc.users.UserRow;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

import javax.sql.DataSource;

/**
 * Builds the central {@link Jdbi} instance: Postgres plugin, SqlObject
 * plugin, and the per-row-record constructor mappers.
 *
 * <p>One Jdbi per DataSource for the lifetime of the application — it's
 * thread-safe and cheap to share. Handles (the per-operation, per-
 * transaction objects) are opened by the UoW as needed.
 *
 * <p>Each row record exposes its own {@code TABLE} constant
 * ({@link UserRow#TABLE}, etc.); the mapper factory is taken from there
 * so the table name and mapper share a single source of truth. Snake-case
 * DB columns map to camelCase components via the default
 * {@code SnakeCaseColumnNameMatcher} JDBI installs.
 */
public final class JdbiSetup {

    private JdbiSetup() {}

    public static Jdbi build(DataSource dataSource) {
        Jdbi jdbi = Jdbi.create(dataSource);
        jdbi.installPlugin(new PostgresPlugin());
        jdbi.installPlugin(new SqlObjectPlugin());

        jdbi.registerRowMapper(UserRow.TABLE.rowMapperFactory());
        jdbi.registerRowMapper(ProcessInstanceRow.TABLE.rowMapperFactory());
        jdbi.registerRowMapper(CredentialsRow.TABLE.rowMapperFactory());
        jdbi.registerRowMapper(RefreshTokenRow.TABLE.rowMapperFactory());

        return jdbi;
    }
}
