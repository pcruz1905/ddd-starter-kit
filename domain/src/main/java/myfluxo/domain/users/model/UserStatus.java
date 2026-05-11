package myfluxo.domain.users.model;

import java.time.Instant;

/**
 * Lifecycle state of a {@link User}, modeled as a sealed interface so the
 * compiler enforces exhaustive handling at every call site.
 *
 * <p>Each state carries the data relevant only to that state — there are no
 * "this field is meaningless when status = X" footguns.
 */
public sealed interface UserStatus {

    /** Just registered; no further action taken. */
    record Pending(Instant since) implements UserStatus {}

    /** Active member; can perform all user actions. */
    record Active(Instant since) implements UserStatus {}

    /** Deactivated with an audited reason and timestamp. */
    record Deactivated(Instant on, String reason) implements UserStatus {}
}
