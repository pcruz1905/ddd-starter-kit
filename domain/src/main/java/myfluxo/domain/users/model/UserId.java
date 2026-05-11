package myfluxo.domain.users.model;

import myfluxo.kernel.id.Identifier;
import myfluxo.kernel.result.Result;
import myfluxo.kernel.id.UuidV7;
import myfluxo.kernel.ddd.ValueObject;

import java.util.UUID;

/**
 * Typed identifier for a User aggregate.
 *
 * <p>Two parsing modes:
 * <ul>
 *     <li>{@link #parse} — returns {@link Result}; use at boundaries
 *         where the input is untrusted (HTTP path params, user input).</li>
 *     <li>{@link #of} — throws; use only when the source is already
 *         trusted (DB rows you just wrote, hardcoded test fixtures).</li>
 * </ul>
 *
 * <p>{@link #newId} produces a UUID v7 — time-ordered, index-friendly.
 */
public record UserId(UUID value) implements Identifier<UUID>, ValueObject {

    public UserId {
        if (value == null) {
            throw new IllegalArgumentException("UserId value cannot be null");
        }
    }

    /** Generate a fresh time-ordered (UUID v7) id for a new aggregate. */
    public static UserId newId() {
        return new UserId(UuidV7.generate());
    }

    /** Parse user-supplied input. Returns a typed {@link ParseError} on bad input. */
    public static Result<UserId, ParseError> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Result.err(new ParseError(raw, "empty"));
        }
        try {
            return Result.ok(new UserId(UUID.fromString(raw.trim())));
        } catch (IllegalArgumentException ex) {
            return Result.err(new ParseError(raw, "not_a_uuid"));
        }
    }

    /** Trusted reconstruction. Throws if the input isn't a valid UUID string. */
    public static UserId of(String raw) {
        try {
            return new UserId(UUID.fromString(raw));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("UserId is not a valid UUID: " + raw, ex);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }

    public record ParseError(String input, String reason) {}
}
