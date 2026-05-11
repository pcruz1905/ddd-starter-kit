package myfluxo.adapter.http.idempotency;

/**
 * Return type from a handler running under {@link IdempotencyMiddleware}.
 * The handler builds business logic + decides the response, the
 * middleware decides whether to cache + replay it.
 *
 * <p>{@code body} is serialized as JSON by the middleware (so the
 * handler hands back a domain DTO, not bytes — same convention as
 * Helidon's regular {@code res.send(body)}).
 */
public record HttpResult(int status, Object body) {
    public HttpResult {
        if (status < 100 || status >= 600) {
            throw new IllegalArgumentException("status out of range: " + status);
        }
    }
}
