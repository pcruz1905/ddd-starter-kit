package myfluxo.adapter.http.auth;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.inject.Singleton;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token-bucket rate limiter for auth endpoints, keyed by
 * caller IP.
 *
 * <h2>Limits</h2>
 * <ul>
 *     <li><b>Login</b>: 5 attempts per 15 minutes. Brute-force defence
 *         on the credentials check. After lockout, the bucket refills
 *         linearly so a few minutes later a fresh attempt is allowed.</li>
 *     <li><b>Register</b>: 3 attempts per hour. Signup-spam defence.
 *         Legitimate users register once; an IP making three signup
 *         attempts in an hour is almost certainly automated.</li>
 * </ul>
 *
 * <h2>Memory bound</h2>
 * Buckets accumulate per IP, never evicted. Bucket size is ~100 bytes
 * each so 10K IPs ≈ 1 MB; acceptable for a starter kit. Production
 * should swap this for a Caffeine-backed cache with TTL eviction
 * (e.g. {@code expireAfterAccess(1 hour)}) or a Redis-backed
 * {@code ProxyManager} when scaling beyond a single node.
 *
 * <h2>Failure mode</h2>
 * {@link #allowLogin}/{@link #allowRegister} return {@code false} when
 * the bucket is exhausted. The HTTP layer maps that to {@code 429}.
 * If an IP is somehow {@code null} (proxy stripped it) the limiter
 * treats it as a single shared bucket — strict on purpose; better
 * over-restrictive than letting an unidentified caller bypass.
 */
@Singleton
public final class AuthRateLimiter {

    static final int LOGIN_CAPACITY = 5;
    static final Duration LOGIN_REFILL = Duration.ofMinutes(15);

    static final int REGISTER_CAPACITY = 3;
    static final Duration REGISTER_REFILL = Duration.ofHours(1);

    private static final String UNKNOWN_IP = "unknown";

    private final Map<String, Bucket> loginBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> registerBuckets = new ConcurrentHashMap<>();

    public boolean allowLogin(String ip) {
        return bucket(loginBuckets, ip, LOGIN_CAPACITY, LOGIN_REFILL).tryConsume(1);
    }

    public boolean allowRegister(String ip) {
        return bucket(registerBuckets, ip, REGISTER_CAPACITY, REGISTER_REFILL).tryConsume(1);
    }

    private static Bucket bucket(
        Map<String, Bucket> store,
        String ip,
        int capacity,
        Duration refill
    ) {
        String key = ip == null || ip.isBlank() ? UNKNOWN_IP : ip;
        return store.computeIfAbsent(key, k -> Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(capacity)
                .refillIntervally(capacity, refill)
                .build())
            .build());
    }
}
