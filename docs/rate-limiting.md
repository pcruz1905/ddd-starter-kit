# Rate limiting

> Token-bucket per IP on auth endpoints. In-memory, single-node. Bucket4j 8.

`adapter-http/src/main/java/myfluxo/adapter/http/auth/AuthRateLimiter.java`

---

## The pattern

Each IP gets its own [token bucket](https://en.wikipedia.org/wiki/Token_bucket) per endpoint. Each request consumes one token. The bucket refills on a fixed schedule. When it hits zero, the next request is refused with `429 Too Many Requests`.

| Endpoint | Capacity | Refill | Rationale |
| --- | --- | --- | --- |
| `POST /v1/auth/login` | 5 | 5 / 15 min | Brute-force defence on credentials check. A legit user mistyping their password 4 times still gets through; an attacker scanning gets locked out after 5. |
| `POST /v1/auth/register` | 3 | 3 / hour | Signup-spam defence. Legitimate users register once; an IP making 3 signups in an hour is almost certainly automated. |

These numbers are deliberate, not magic. They balance:

- **False positives**: a real user fat-fingering their password should not be locked out
- **Attack cost**: an attacker scanning common passwords hits the wall after the first batch

If your threat model is different (B2B portal vs consumer app), tune the constants. They live at the top of `AuthRateLimiter`.

---

## Why token bucket, not "fixed window"

| Token bucket | Fixed window |
| --- | --- |
| Refills smoothly — a moment after lockout, a single request goes through | All-or-nothing window boundary; attacker fires bursts at boundaries |
| Burst-tolerant: you can fire 5 in a second, then 0 for 15 min | Burst on the boundary doubles effective rate |
| Each bucket is `~100 bytes` | Similar |

Bucket4j is the standard Java implementation. Mature, well-tested, well-documented.

---

## Implementation

```java
private final Map<String, Bucket> loginBuckets = new ConcurrentHashMap<>();

public boolean allowLogin(String ip) {
    return bucket(loginBuckets, ip, LOGIN_CAPACITY, LOGIN_REFILL).tryConsume(1);
}

private static Bucket bucket(Map<String, Bucket> store, String ip,
                             int capacity, Duration refill) {
    String key = ip == null || ip.isBlank() ? "unknown" : ip;
    return store.computeIfAbsent(key, k -> Bucket.builder()
        .addLimit(Bandwidth.builder()
            .capacity(capacity)
            .refillIntervally(capacity, refill)
            .build())
        .build());
}
```

`computeIfAbsent` is thread-safe. `tryConsume` is lock-free.

### Wire-up in the route

```java
routes.post("/v1/auth/login", (req, res) -> {
    if (!rateLimiter.allowLogin(req.remotePeer().host())) {
        res.status(429).send(ErrorResponse.of("rate_limited", "Too many login attempts. Try again later."));
        return;
    }
    // ... normal handling ...
});
```

---

## What you should swap for production scale

The shipped implementation is **single-node, in-memory**. That has two limits:

1. **No eviction.** Buckets accumulate per IP forever. ~100 bytes each means 10K IPs ≈ 1 MB — fine for a small to mid app. For a large surface, swap the `ConcurrentHashMap` for a Caffeine cache with `expireAfterAccess(1.hour)`:

   ```java
   private final Cache<String, Bucket> loginBuckets =
       Caffeine.newBuilder().expireAfterAccess(Duration.ofHours(1)).build();
   ```

2. **Per-node state.** Three app instances behind a load balancer means three independent buckets per IP — effectively triple the limit. Swap for Bucket4j's `ProxyManager` backed by Redis when you scale horizontally:

   ```java
   ProxyManager<String> proxy = LettuceBasedProxyManager.builderFor(redisClient)
       .withExpirationStrategy(...).build();
   Bucket bucket = proxy.builder().build(ipKey, () -> bucketConfig);
   ```

Both swaps are local to `AuthRateLimiter` — the call sites don't change.

---

## What the limiter does NOT do

- **Account lockout.** Five wrong passwords from one IP lock that IP, not the account. Account lockout has its own UX trade-offs (DOS-by-locking-someone's-account) and isn't shipped here. Add it as a counter on `users` if you want it.
- **CAPTCHA escalation.** Some apps want "show a CAPTCHA after 3 attempts, lock after 5". That's a sequencing UI concern, not a kit primitive.
- **IP allow/deny lists.** Plain `Map<String, ...>` lookup if you need it; route-level middleware is the right place.
- **Distributed tracing of rate-limit events.** All the limiter knows is "allowed: bool". If you want metrics (rejections per minute, top abusers), wrap the methods in your observability layer.

---

## End-to-end proof

`AuthRoutesIT.login_burst_eventuallyRateLimited` fires 10 logins at the same IP and asserts that at least one returns 429. Documented in [`docs/auth.md`](auth.md).

---

## See also

- [`docs/auth.md`](auth.md) — login + register handlers that call the limiter
- [`docs/audit-log.md`](audit-log.md) — `loginFailure` audit events still fire even when rate-limited (rate-limit refusals also log)
