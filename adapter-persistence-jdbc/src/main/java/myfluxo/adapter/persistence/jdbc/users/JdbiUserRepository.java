package myfluxo.adapter.persistence.jdbc.users;

import jakarta.inject.Singleton;
import myfluxo.adapter.persistence.jdbc.JdbiAggregateRepository;
import myfluxo.adapter.persistence.jdbc.TransactionalHandle;
import myfluxo.domain.shared.model.Email;
import myfluxo.domain.users.User;
import myfluxo.domain.users.UserRepository;
import myfluxo.domain.users.model.UserId;

import java.util.Optional;

/**
 * JDBI + Postgres implementation of {@link UserRepository}.
 *
 * <p>Inherits {@code findById / save / delete / restore} from
 * {@link JdbiAggregateRepository}. Only the User-specific finders
 * ({@code findByEmail}, {@code existsByEmail}) and the three
 * aggregate↔row bridges live here.
 */
@Singleton
public final class JdbiUserRepository
    extends JdbiAggregateRepository<User, UserId, UserRow>
    implements UserRepository {

    private static final String COL_EMAIL = UserRow.TABLE.col("email");

    private static final String FIND_BY_EMAIL =
        UserRow.TABLE.selectAll()
        + " WHERE LOWER(" + COL_EMAIL + ") = LOWER(:email)";
    private static final String EXISTS_BY_EMAIL =
        UserRow.TABLE.existsWhere("LOWER(" + COL_EMAIL + ") = LOWER(:email)");

    public JdbiUserRepository(TransactionalHandle tx) {
        // "createdAt" is excluded from UPDATE SET — immutable audit column.
        super(tx, UserRow.TABLE, "createdAt");
    }

    @Override
    protected UserRow toRow(User user, long newVersion) {
        return UserRow.fromAggregate(user, newVersion);
    }

    @Override
    protected User toAggregate(UserRow row) {
        return row.toAggregate();
    }

    @Override
    protected UserId idFromRow(UserRow row) {
        return new UserId(row.id());
    }

    @Override
    public Optional<User> findByEmail(Email email) {
        return tx.withHandle(h -> h.createQuery(FIND_BY_EMAIL)
            .bind("email", email.value())
            .mapTo(UserRow.class)
            .findFirst())
            .map(UserRow::toAggregate);
    }

    @Override
    public boolean existsByEmail(Email email) {
        return tx.withHandle(h -> h.createQuery(EXISTS_BY_EMAIL)
            .bind("email", email.value())
            .mapTo(Boolean.class)
            .one());
    }
}
