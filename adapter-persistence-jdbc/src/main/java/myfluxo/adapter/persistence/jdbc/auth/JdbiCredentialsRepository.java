package myfluxo.adapter.persistence.jdbc.auth;

import jakarta.inject.Singleton;
import myfluxo.adapter.persistence.jdbc.JdbiAggregateRepository;
import myfluxo.adapter.persistence.jdbc.TransactionalHandle;
import myfluxo.domain.auth.Credentials;
import myfluxo.domain.auth.CredentialsRepository;
import myfluxo.domain.users.model.UserId;

import java.util.Optional;

/**
 * JDBI + Postgres implementation of {@link CredentialsRepository}.
 * CRUD inherited from {@link JdbiAggregateRepository}; the only
 * Credentials-specific method ({@link #findByUserId}) routes to the
 * inherited {@link #findById} since the aggregate's identity IS the
 * user id.
 */
@Singleton
public final class JdbiCredentialsRepository
    extends JdbiAggregateRepository<Credentials, UserId, CredentialsRow>
    implements CredentialsRepository {

    public JdbiCredentialsRepository(TransactionalHandle tx) {
        // createdAt is immutable; everything else may move on update.
        super(tx, CredentialsRow.TABLE, "createdAt");
    }

    @Override
    protected CredentialsRow toRow(Credentials c, long newVersion) {
        return CredentialsRow.fromAggregate(c, newVersion);
    }

    @Override
    protected Credentials toAggregate(CredentialsRow row) {
        return row.toAggregate();
    }

    @Override
    protected UserId idFromRow(CredentialsRow row) {
        return new UserId(row.id());
    }

    @Override
    public Optional<Credentials> findByUserId(UserId userId) {
        return findById(userId);
    }
}
