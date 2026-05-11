package myfluxo.domain.auth.model;

import myfluxo.kernel.ddd.ValueObject;

import java.util.Set;

/**
 * The set of permissions assigned to a user. Sealed: every variant must
 * be handled exhaustively by callers, and the compiler enforces this.
 *
 * <h2>Why sealed, not an open enum/registry</h2>
 * Roles are deliberately closed at compile time. Adding a role is a
 * code change with a PR and a review; it can't be done by an admin
 * via UI. This keeps the authorization surface predictable and
 * auditable, at the cost of operational flexibility. For systems
 * needing self-service role definition, replace this with an open
 * record + persistence; for a starter kit, closed is the right default.
 *
 * <h2>Catalog</h2>
 * <ul>
 *     <li>{@link Admin} — every permission.</li>
 *     <li>{@link Member} — read + write on the user's own resources.</li>
 *     <li>{@link Viewer} — read-only.</li>
 * </ul>
 */
public sealed interface Role extends ValueObject permits Role.Admin, Role.Member, Role.Viewer {

    /** Permissions held by this role. */
    Set<Permission> permissions();

    /** Wire name. Stable across versions — do not rename casually. */
    String name();

    default boolean hasPermission(Permission permission) {
        return permissions().contains(permission);
    }

    static Role fromName(String name) {
        return switch (name) {
            case Admin.NAME -> Admin.INSTANCE;
            case Member.NAME -> Member.INSTANCE;
            case Viewer.NAME -> Viewer.INSTANCE;
            default -> throw new IllegalArgumentException(
                "Unknown role name: " + name + " (valid: ADMIN, MEMBER, VIEWER)");
        };
    }

    final class Admin implements Role {
        public static final String NAME = "ADMIN";
        public static final Admin INSTANCE = new Admin();
        private Admin() {}
        @Override public Set<Permission> permissions() { return Permission.ALL; }
        @Override public String name() { return NAME; }
        @Override public String toString() { return NAME; }
    }

    final class Member implements Role {
        public static final String NAME = "MEMBER";
        public static final Member INSTANCE = new Member();
        private Member() {}
        @Override public Set<Permission> permissions() {
            return Set.of(Permission.USERS_READ, Permission.USERS_WRITE);
        }
        @Override public String name() { return NAME; }
        @Override public String toString() { return NAME; }
    }

    final class Viewer implements Role {
        public static final String NAME = "VIEWER";
        public static final Viewer INSTANCE = new Viewer();
        private Viewer() {}
        @Override public Set<Permission> permissions() {
            return Set.of(Permission.USERS_READ);
        }
        @Override public String name() { return NAME; }
        @Override public String toString() { return NAME; }
    }
}
