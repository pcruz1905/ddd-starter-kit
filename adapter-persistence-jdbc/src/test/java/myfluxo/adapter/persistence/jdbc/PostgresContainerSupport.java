package myfluxo.adapter.persistence.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import org.jdbi.v3.core.Jdbi;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers Postgres for integration tests. The container is
 * started lazily on first use and reused across all suites in this JVM.
 *
 * <p><b>Container reuse across JVMs</b>: {@code withReuse(true)} keeps
 * the container alive between test runs locally — so the second
 * {@code mvn verify} skips the ~5s Postgres boot and runs the test
 * suite in seconds. To enable it on your machine, add this line to
 * {@code ~/.testcontainers.properties} (create the file if missing):
 *
 * <pre>testcontainers.reuse.enable=true</pre>
 *
 * <p>Without that flag Testcontainers ignores {@code withReuse(true)}
 * and starts a fresh container per run — safer in CI, slower locally.
 *
 * <p>Each test should clean affected tables at start to keep tests
 * independent across reused container runs.
 */
public final class PostgresContainerSupport {

    private static final DockerImageName POSTGRES_IMAGE =
        DockerImageName.parse("postgres:17-alpine");

    private static volatile PostgreSQLContainer<?> container;
    private static volatile HikariDataSource dataSource;
    private static volatile Jdbi jdbi;

    private PostgresContainerSupport() {}

    public static synchronized HikariDataSource dataSource() {
        if (dataSource == null) {
            container = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("myfluxo_test")
                .withUsername("test")
                .withPassword("test")
                .withReuse(true);
            container.start();

            var config = JdbcDataSourceFactory.Config.defaults(
                container.getJdbcUrl(),
                container.getUsername(),
                container.getPassword()
            );
            dataSource = JdbcDataSourceFactory.build(config);
            JdbcDataSourceFactory.migrate(dataSource, config);
        }
        return dataSource;
    }

    public static synchronized Jdbi jdbi() {
        if (jdbi == null) {
            jdbi = JdbiSetup.build(dataSource());
        }
        return jdbi;
    }
}
