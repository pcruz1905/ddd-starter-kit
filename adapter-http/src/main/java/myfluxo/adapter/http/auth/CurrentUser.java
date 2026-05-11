package myfluxo.adapter.http.auth;

import myfluxo.domain.auth.model.Role;
import myfluxo.domain.users.model.UserId;

import java.util.Objects;

/**
 * The authenticated caller for an HTTP request. Built from the verified
 * access-token claims by {@link JwtBearerAuth}, then handed to route
 * handlers that need to know who's calling.
 */
public record CurrentUser(UserId userId, Role role) {

    public CurrentUser {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
    }

    public boolean hasPermission(myfluxo.domain.auth.model.Permission permission) {
        return role.hasPermission(permission);
    }
}
