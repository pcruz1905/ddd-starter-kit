package myfluxo.adapter.persistence.jdbc.auth;

import myfluxo.adapter.persistence.jdbc.Table;
import myfluxo.domain.auth.Credentials;
import myfluxo.domain.auth.model.PasswordHash;
import myfluxo.domain.users.model.UserId;
import org.jdbi.v3.core.mapper.reflect.ColumnName;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape of the {@code credentials} table.
 *
 * <p>Credentials are 1:1 with User and identified by {@link UserId};
 * the PK column is {@code user_id}. The row's {@code id} component
 * (annotated to map to {@code user_id}) satisfies {@link Table}'s
 * id-component convention so the base-class CRUD machinery works.
 */
public record CredentialsRow(
    @ColumnName("user_id") UUID id,
    String passwordHash,
    Instant createdAt,
    Instant updatedAt,
    long version
) {

    public static final Table<CredentialsRow> TABLE =
        Table.of("credentials", CredentialsRow.class);

    public static CredentialsRow fromAggregate(Credentials c, long newVersion) {
        return new CredentialsRow(
            c.userId().value(),
            c.passwordHash().encoded(),
            c.createdAt(),
            c.updatedAt(),
            newVersion
        );
    }

    public Credentials toAggregate() {
        return Credentials.rehydrate(
            new UserId(id),
            PasswordHash.of(passwordHash),
            createdAt,
            updatedAt,
            version
        );
    }
}
