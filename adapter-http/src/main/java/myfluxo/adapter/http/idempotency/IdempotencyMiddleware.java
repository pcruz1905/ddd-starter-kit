package myfluxo.adapter.http.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import jakarta.inject.Singleton;
import myfluxo.adapter.http.ErrorResponse;
import myfluxo.kernel.idempotency.IdempotencyKey;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.function.Function;

/**
 * Stripe / IETF "Idempotency-Key" RFC middleware. Wraps a request
 * handler with replay-from-cache behaviour:
 *
 * <ol>
 *     <li>No {@code Idempotency-Key} header → run handler, send result
 *         normally.</li>
 *     <li>Key present, cache miss → run handler, capture
 *         {@code (status, body)}, store, send.</li>
 *     <li>Key present, cache hit, same request body hash → replay the
 *         stored bytes verbatim (with {@code X-Idempotent-Replay: true}
 *         header).</li>
 *     <li>Key present, cache hit, <em>different</em> body hash →
 *         {@code 422 Unprocessable Entity} (key reuse with a different
 *         payload is a client bug).</li>
 * </ol>
 *
 * <p>Use cases stay clean — they don't know they're being deduplicated.
 * Adding a new idempotent endpoint is one line: wrap its handler in
 * {@link #run}.
 */
@Singleton
public final class IdempotencyMiddleware {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String REPLAY_HEADER = "X-Idempotent-Replay";
    private static final String DEFAULT_CONTENT_TYPE = "application/json";

    private final IdempotencyCache cache;
    private final ObjectMapper json;

    public IdempotencyMiddleware(IdempotencyCache cache, ObjectMapper json) {
        this.cache = cache;
        this.json = json;
    }

    /**
     * Run {@code handler} under idempotency semantics. The handler
     * receives the raw request bytes (already buffered) and returns
     * an {@link HttpResult}; the middleware does the rest.
     */
    public void run(
        ServerRequest req,
        ServerResponse res,
        Function<byte[], HttpResult> handler
    ) {
        byte[] requestBody = req.content().as(byte[].class);
        Optional<IdempotencyKey> keyOpt = readKey(req);

        if (keyOpt.isEmpty()) {
            send(res, handler.apply(requestBody));
            return;
        }

        IdempotencyKey key = keyOpt.get();
        String requestHash = sha256Hex(requestBody);

        Optional<CachedResponse> cached = cache.find(key);
        if (cached.isPresent()) {
            if (!cached.get().requestHash().equals(requestHash)) {
                sendKeyReuseConflict(res, key);
                return;
            }
            replay(res, cached.get());
            return;
        }

        // Cache miss — run, capture, store, send.
        HttpResult result = handler.apply(requestBody);
        byte[] bodyBytes = serialize(result.body());
        cache.put(key, new CachedResponse(
            requestHash, result.status(), bodyBytes, DEFAULT_CONTENT_TYPE));
        sendBytes(res, result.status(), bodyBytes);
    }

    private void send(ServerResponse res, HttpResult result) {
        res.status(Status.create(result.status())).send(result.body());
    }

    private void sendBytes(ServerResponse res, int status, byte[] body) {
        res.status(Status.create(status))
            .header(HeaderNames.CONTENT_TYPE, DEFAULT_CONTENT_TYPE)
            .send(body);
    }

    private void replay(ServerResponse res, CachedResponse cached) {
        var contentType = cached.contentType() == null ? DEFAULT_CONTENT_TYPE : cached.contentType();
        res.status(Status.create(cached.statusCode()))
            .header(HeaderNames.CONTENT_TYPE, contentType)
            .header(HeaderNames.create(REPLAY_HEADER), "true")
            .send(cached.body());
    }

    private void sendKeyReuseConflict(ServerResponse res, IdempotencyKey key) {
        res.status(Status.UNPROCESSABLE_CONTENT_422).send(ErrorResponse.of(
            "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_BODY",
            "Idempotency-Key '" + key.value() + "' was previously used with a "
                + "different request body. Generate a new key for new requests."
        ));
    }

    private static Optional<IdempotencyKey> readKey(ServerRequest req) {
        return req.headers().first(HeaderNames.create(IDEMPOTENCY_HEADER))
            .filter(v -> !v.isBlank())
            .map(IdempotencyKey::new);
    }

    private byte[] serialize(Object body) {
        try {
            return json.writeValueAsBytes(body);
        } catch (Exception ex) {
            throw new IllegalStateException(
                "Failed to serialize response body of type "
                    + (body == null ? "null" : body.getClass().getName()), ex);
        }
    }

    private static String sha256Hex(byte[] body) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(body == null ? new byte[0] : body);
            var hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 must be available on every JVM", ex);
        }
    }
}
