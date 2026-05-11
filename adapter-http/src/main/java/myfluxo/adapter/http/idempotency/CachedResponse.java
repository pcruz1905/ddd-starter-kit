package myfluxo.adapter.http.idempotency;

/**
 * One row of {@link IdempotencyCache}: the bytes the handler produced
 * on first execution, plus enough metadata to faithfully replay them.
 *
 * <p>{@code requestHash} is a SHA-256 hex digest of the request body.
 * The middleware compares it on cache hit so that reusing the same key
 * with a different payload yields a 422, not a wrong-but-cached reply.
 */
public record CachedResponse(
    String requestHash,
    int statusCode,
    byte[] body,
    String contentType
) {
    public CachedResponse {
        if (requestHash == null || requestHash.isBlank()) {
            throw new IllegalArgumentException("requestHash must be non-blank");
        }
        if (statusCode < 100 || statusCode >= 600) {
            throw new IllegalArgumentException("statusCode out of range: " + statusCode);
        }
        if (body == null) {
            throw new NullPointerException("body");
        }
    }
}
