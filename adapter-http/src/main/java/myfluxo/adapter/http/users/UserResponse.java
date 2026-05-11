package myfluxo.adapter.http.users;

import myfluxo.domain.users.User;
import myfluxo.domain.users.model.UserStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire-level shape for a User. Decouples the HTTP contract from the
 * aggregate's internals — domain refactors don't have to change the API.
 */
public record UserResponse(
    UUID id,
    String email,
    String status,
    Instant statusSince,
    String deactivationReason,
    Instant createdAt
) {

    public static UserResponse from(User user) {
        return switch (user.status()) {
            case UserStatus.Pending p -> new UserResponse(
                user.id().value(), user.email().value(), "pending", p.since(), null, user.createdAt()
            );
            case UserStatus.Active a -> new UserResponse(
                user.id().value(), user.email().value(), "active", a.since(), null, user.createdAt()
            );
            case UserStatus.Deactivated d -> new UserResponse(
                user.id().value(), user.email().value(), "deactivated", d.on(), d.reason(), user.createdAt()
            );
        };
    }
}
