package myfluxo.domain.auth.model;

import myfluxo.kernel.ddd.ValueObject;

import java.util.Objects;
import java.util.Set;

/**
 * A fine-grained action on a resource — the unit of authorization.
 * Identity is the pair {@code (resource, action)}; equality is value
 * equality. Permissions are grouped into {@link Role}s; a user is
 * authorized for a permission if any of their roles holds it.
 *
 * <h2>Naming</h2>
 * <ul>
 *     <li>{@code resource}: a lowercase plural domain noun
 *         — {@code users}, {@code products}, {@code orders}.</li>
 *     <li>{@code action}: a lowercase verb — {@code read}, {@code write},
 *         {@code delete}. Read covers list+get; write covers create+update.
 *         Add finer verbs only when a role needs the distinction.</li>
 * </ul>
 *
 * <p>Catalog constants live below. Adding a new permission goes:
 * <ol>
 *     <li>add the {@code public static final Permission} here,</li>
 *     <li>add it to whichever role(s) should hold it in {@link Role},</li>
 *     <li>enforce at the call site via
 *         {@code role.hasPermission(Permission.X)}.</li>
 * </ol>
 */
public record Permission(String resource, String action) implements ValueObject {

    public Permission {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(action, "action");
        if (resource.isBlank()) {
            throw new IllegalArgumentException("resource cannot be blank");
        }
        if (action.isBlank()) {
            throw new IllegalArgumentException("action cannot be blank");
        }
        if (!resource.equals(resource.toLowerCase())) {
            throw new IllegalArgumentException(
                "resource must be lowercase: " + resource);
        }
        if (!action.equals(action.toLowerCase())) {
            throw new IllegalArgumentException(
                "action must be lowercase: " + action);
        }
    }

    /** Canonical wire form: {@code resource:action}. */
    public String name() {
        return resource + ":" + action;
    }

    @Override
    public String toString() {
        return name();
    }

    // ── Catalog ─────────────────────────────────────────────────────

    public static final Permission USERS_READ = new Permission("users", "read");
    public static final Permission USERS_WRITE = new Permission("users", "write");
    public static final Permission USERS_DELETE = new Permission("users", "delete");

    /** Every permission known to the system. {@link Role.Admin} holds all. */
    public static final Set<Permission> ALL = Set.of(
        USERS_READ,
        USERS_WRITE,
        USERS_DELETE
    );
}
