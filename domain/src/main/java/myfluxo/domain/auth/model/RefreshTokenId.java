package myfluxo.domain.auth.model;

import myfluxo.kernel.ddd.ValueObject;
import myfluxo.kernel.id.Identifier;
import myfluxo.kernel.id.UuidV7;
import myfluxo.kernel.result.Result;

import java.util.UUID;

/**
 * Typed identifier for a {@code RefreshToken} aggregate.
 *
 * <p>UUID v7 — time-ordered, B-tree-friendly, sortable by issue order.
 * Useful operationally: listing recent tokens by id gives chronological
 * order without joining on {@code created_at}.
 */
public record RefreshTokenId(UUID value) implements Identifier<UUID>, ValueObject {

    public RefreshTokenId {
        if (value == null) {
            throw new IllegalArgumentException("RefreshTokenId value cannot be null");
        }
    }

    public static RefreshTokenId newId() {
        return new RefreshTokenId(UuidV7.generate());
    }

    public static Result<RefreshTokenId, ParseError> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Result.err(new ParseError(raw, "empty"));
        }
        try {
            return Result.ok(new RefreshTokenId(UUID.fromString(raw.trim())));
        } catch (IllegalArgumentException ex) {
            return Result.err(new ParseError(raw, "not_a_uuid"));
        }
    }

    public static RefreshTokenId of(String raw) {
        try {
            return new RefreshTokenId(UUID.fromString(raw));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                "RefreshTokenId is not a valid UUID: " + raw, ex);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }

    public record ParseError(String input, String reason) {}
}
