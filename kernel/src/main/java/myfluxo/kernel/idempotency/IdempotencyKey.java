package myfluxo.kernel.idempotency;

import myfluxo.kernel.ddd.ValueObject;

/**
 * Caller-supplied idempotency key — the client's promise that "this is the
 * same logical command as one you've seen with this same key". On retry,
 * a use case should return the same outcome rather than re-executing.
 *
 * <p>Typically passed in an HTTP header like {@code Idempotency-Key}.
 * The HTTP-layer idempotency middleware reads the header, hashes the
 * request body, and replays the cached response on retries.
 */
public record IdempotencyKey(String value) implements ValueObject {

    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("IdempotencyKey value cannot be null/blank");
        }
        if (value.length() > 200) {
            throw new IllegalArgumentException("IdempotencyKey is too long (max 200 chars)");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
