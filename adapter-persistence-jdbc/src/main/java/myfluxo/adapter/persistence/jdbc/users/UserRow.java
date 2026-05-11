package myfluxo.adapter.persistence.jdbc.users;

import myfluxo.adapter.persistence.jdbc.Table;
import myfluxo.domain.auth.model.Role;
import myfluxo.domain.shared.model.Email;
import myfluxo.domain.users.User;
import myfluxo.domain.users.model.UserId;
import myfluxo.domain.users.model.UserStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape of the {@code users} table. Component names are the JDBI
 * bind parameters (camelCase); column names are derived to snake_case
 * by {@code RecordSql} unless overridden with JDBI's
 * {@code @ColumnName} on the accessor.
 *
 * <p>One source of truth for the column set: adding a new column means
 * adding a component here — the SELECT projection, INSERT placeholders,
 * UPDATE SET clause, and ConstructorMapper-driven read path all pick
 * it up. The aggregate ↔ row translation still has to acknowledge the
 * new field (that's correct — somebody has to decide how it maps to a
 * domain concept).
 */
public record UserRow(
    UUID id,
    String email,
    String statusType,
    Instant statusSince,
    String statusDeactivationReason,
    String role,
    Instant createdAt,
    long version
) {

    public static final Table<UserRow> TABLE = Table.of("users", UserRow.class);

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_ACTIVE = "ACTIVE";
    static final String STATUS_DEACTIVATED = "DEACTIVATED";

    public static UserRow fromAggregate(User user, long version) {
        var status = user.status();
        return new UserRow(
            user.id().value(),
            user.email().value(),
            statusType(status),
            statusSince(status),
            statusReason(status),
            user.role().name(),
            user.createdAt(),
            version
        );
    }

    public User toAggregate() {
        return User.rehydrate(
            new UserId(id),
            new Email(email),
            decodeStatus(),
            Role.fromName(role),
            createdAt,
            version
        );
    }

    private UserStatus decodeStatus() {
        return switch (statusType) {
            case STATUS_PENDING -> new UserStatus.Pending(statusSince);
            case STATUS_ACTIVE -> new UserStatus.Active(statusSince);
            case STATUS_DEACTIVATED ->
                new UserStatus.Deactivated(statusSince, statusDeactivationReason);
            default -> throw new IllegalStateException(
                "Unknown status_type in DB: " + statusType + " (id=" + id + ")"
            );
        };
    }

    private static String statusType(UserStatus status) {
        return switch (status) {
            case UserStatus.Pending _ -> STATUS_PENDING;
            case UserStatus.Active _ -> STATUS_ACTIVE;
            case UserStatus.Deactivated _ -> STATUS_DEACTIVATED;
        };
    }

    private static Instant statusSince(UserStatus status) {
        return switch (status) {
            case UserStatus.Pending p -> p.since();
            case UserStatus.Active a -> a.since();
            case UserStatus.Deactivated d -> d.on();
        };
    }

    private static String statusReason(UserStatus status) {
        return switch (status) {
            case UserStatus.Deactivated d -> d.reason();
            case UserStatus.Pending _, UserStatus.Active _ -> null;
        };
    }
}
