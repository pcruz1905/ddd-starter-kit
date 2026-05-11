package myfluxo.adapter.persistence.jdbc.auth;

import jakarta.inject.Singleton;
import myfluxo.adapter.persistence.jdbc.JdbiAggregateRepository;
import myfluxo.adapter.persistence.jdbc.TransactionalHandle;
import myfluxo.domain.auth.RefreshToken;
import myfluxo.domain.auth.RefreshTokenRepository;
import myfluxo.domain.auth.model.RefreshTokenFamilyId;
import myfluxo.domain.auth.model.RefreshTokenId;
import myfluxo.domain.auth.model.TokenHash;
import myfluxo.domain.users.model.UserId;

import java.time.Instant;
import java.util.Optional;

/**
 * JDBI + Postgres implementation of {@link RefreshTokenRepository}.
 *
 * <p>{@link JdbiAggregateRepository} supplies {@code findById}/{@code save}
 * via the row record. Three custom queries layer on top:
 *
 * <ul>
 *     <li>{@link #findByTokenHash} — the lookup path used on every
 *         refresh/logout. Hits the unique index on {@code token_hash}.</li>
 *     <li>{@link #revokeFamily} — bulk-revoke every active token sharing
 *         a family id. Used on theft detection.</li>
 *     <li>{@link #revokeAllForUser} — bulk-revoke every active token for
 *         a user. Used on password change.</li>
 * </ul>
 *
 * <p>The bulk revokes use a single {@code UPDATE} so the work is one
 * round-trip; they bypass aggregate hydration deliberately (no need to
 * load each token to mutate it). They DO bump the version column so
 * downstream optimistic-concurrency consumers see the change.
 */
@Singleton
public final class JdbiRefreshTokenRepository
    extends JdbiAggregateRepository<RefreshToken, RefreshTokenId, RefreshTokenRow>
    implements RefreshTokenRepository {

    private static final String FIND_BY_HASH =
        RefreshTokenRow.TABLE.selectAll() + " WHERE token_hash = :tokenHash";

    private static final String REVOKE_FAMILY =
        "UPDATE " + RefreshTokenRow.TABLE.name()
        + " SET revoked_at = :now, version = version + 1"
        + " WHERE family_id = :familyId AND revoked_at IS NULL";

    private static final String REVOKE_ALL_FOR_USER =
        "UPDATE " + RefreshTokenRow.TABLE.name()
        + " SET revoked_at = :now, version = version + 1"
        + " WHERE user_id = :userId AND revoked_at IS NULL";

    public JdbiRefreshTokenRepository(TransactionalHandle tx) {
        // createdAt is immutable; everything else (revokedAt, replacedByTokenId,
        // version) may move on update.
        super(tx, RefreshTokenRow.TABLE, "createdAt");
    }

    @Override
    protected RefreshTokenRow toRow(RefreshToken t, long newVersion) {
        return RefreshTokenRow.fromAggregate(t, newVersion);
    }

    @Override
    protected RefreshToken toAggregate(RefreshTokenRow row) {
        return row.toAggregate();
    }

    @Override
    protected RefreshTokenId idFromRow(RefreshTokenRow row) {
        return new RefreshTokenId(row.id());
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(TokenHash tokenHash) {
        return tx.withHandle(h -> h.createQuery(FIND_BY_HASH)
            .bind("tokenHash", tokenHash.encoded())
            .mapTo(RefreshTokenRow.class)
            .findFirst())
            .map(RefreshTokenRow::toAggregate);
    }

    @Override
    public void revokeFamily(RefreshTokenFamilyId familyId, Instant now) {
        tx.useHandle(h -> h.createUpdate(REVOKE_FAMILY)
            .bind("now", now)
            .bind("familyId", familyId.value())
            .execute());
    }

    @Override
    public void revokeAllForUser(UserId userId, Instant now) {
        tx.useHandle(h -> h.createUpdate(REVOKE_ALL_FOR_USER)
            .bind("now", now)
            .bind("userId", userId.value())
            .execute());
    }
}
