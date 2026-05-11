# Auth

> Argon2id passwords. HS256 access tokens. Rotating refresh tokens with theft detection. Timing-safe login. Designed against the OWASP cheat sheet.

The kit ships a complete auth stack you can fork as-is. The decisions below are not defaults — each one is deliberate, and each one trades off a real attack surface.

---

## The endpoints

`adapter-http/src/main/java/myfluxo/adapter/http/auth/AuthRoutes.java`

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| `POST` | `/v1/auth/register` | — | Rate-limited: 3 / hour / IP |
| `POST` | `/v1/auth/login` | — | Rate-limited: 5 / 15 min / IP |
| `POST` | `/v1/auth/refresh` | — | Rotates RT; detects reuse |
| `POST` | `/v1/auth/logout` | — | Revokes the presented RT |
| `POST` | `/v1/auth/change-password` | Bearer | Revokes all the user's RTs |
| `GET`  | `/v1/auth/me` | Bearer | Returns `{userId, role}` |

---

## Password hashing — Argon2id

`adapter-auth/src/main/java/myfluxo/adapter/auth/Argon2PasswordHasher.java`

[Argon2id](https://en.wikipedia.org/wiki/Argon2) is the **OWASP-recommended default**. It is the only widely-deployed hash that defends against both GPU and ASIC attackers in a single algorithm.

Parameters per OWASP's [Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#argon2id) (2025 baseline):

| Parameter | Value | Why |
| --- | --- | --- |
| Memory | 19 MiB | Forces GPU attackers into VRAM contention |
| Iterations | 2 | Tuned to ~50 ms per verify on a modern server |
| Parallelism | 1 | Single-thread per verify keeps tail latency low |
| Salt | 16 bytes random | Per-password salt prevents rainbow tables |
| Output | 32 bytes | Standard |

Library: [Password4j](https://password4j.com/). The encoded hash carries its own parameters (`$argon2id$v=19$m=19456,t=2,p=1$...`), so bumping the cost factor in the future does not invalidate stored hashes — Password4j's `update` method will re-hash on next successful login. (Not wired by default; one if-branch in `Login` away.)

### Timing-attack defense

A naïve login is timing-vulnerable:

```java
// VULNERABLE
var user = users.findByEmail(email);
if (user.isEmpty()) {
    return Err(InvalidCredentials);  // ←─── fast path
}
if (!hasher.verify(password, user.get().passwordHash())) {
    return Err(InvalidCredentials);  // ←─── slow path (~50 ms)
}
```

The fast/slow split lets an attacker enumerate valid emails by **measuring response time**. Email enumeration is a precursor to credential stuffing and phishing.

`Login.java` mitigates this with a **precomputed decoy hash**, generated once at construction:

```java
public Login(...) {
    // ... wire dependencies ...
    this.decoyHash = hasher.hash(DECOY_PLAINTEXT);   // ← built once
}

public Result<AuthSession, AuthError> handle(LoginCommand cmd) {
    var user = users.findByEmail(cmd.email());
    if (user.isEmpty()) {
        hasher.verify(cmd.password(), decoyHash);    // burns ~50 ms anyway
        return Result.err(new AuthError.InvalidCredentials());
    }
    // ... normal verify ...
}
```

Both paths now take the same time. The error response is also identical (`InvalidCredentials`), so the attacker has no signal — neither timing nor message — to distinguish "user doesn't exist" from "wrong password".

This is enforced by tests in `application/src/test/.../LoginTest.java` (response shape) and provable at the wire by `AuthRoutesIT`.

---

## Access tokens — HS256 JWT

`adapter-auth/src/main/java/myfluxo/adapter/auth/JwtTokenIssuer.java`

| Property | Value | Why |
| --- | --- | --- |
| Algorithm | HS256 | Symmetric; no key-distribution problem for single-process / monolith |
| Secret length | ≥32 bytes | Required by the JWT library; enforced at startup |
| TTL | 15 min (default) | Short-lived; bounds exposure if a token leaks |
| Claims | `sub` = userId, `iss` = `myfluxo`, `iat`, `exp`, custom `role` | Minimal — no PII in the token |

**Why not RS256?** RS256 buys you "the verifier doesn't need the signing secret" — useful when many services verify tokens issued by one auth service. For a single-process kit, that asymmetry is unused complexity, and HS256 is faster (~5× verify speed).

If you split this kit into multiple deploys later, swap `JwtTokenIssuer` for an `RsaJwtTokenIssuer` — the `TokenIssuer` port stays the same.

### What the access token does NOT do

- **No session lookups.** The token is stateless — the userId and role come from the claims directly, no DB round trip per request. This is the speed win of JWTs.
- **No revocation.** Stateless = unrevocable until expiry. A 15-minute TTL means a compromised token has a 15-minute upper bound on damage. **Refresh tokens** handle revocation (see below).

---

## Refresh tokens — rotation + family revocation

This is the most interesting piece. The pattern is canonical, but it's worth seeing exactly why each design decision is there.

`domain/src/main/java/myfluxo/domain/auth/RefreshToken.java`
`application/src/main/java/myfluxo/application/auth/usecases/RefreshSession.java`

### What you hand the client

```
{
  "accessToken":  "<jwt, expires in 15 min>",
  "refreshToken": "<opaque-256-bit random, expires in 7 days>"
}
```

The refresh token is **not a JWT**. It's 32 bytes of random, base64url-encoded. The server stores only an HMAC-SHA256 of it.

### What the server stores

```sql
CREATE TABLE refresh_tokens (
    id                     UUID PRIMARY KEY,
    user_id                UUID NOT NULL REFERENCES users(id),
    token_hash             BYTEA NOT NULL UNIQUE,   -- HMAC-SHA256(plaintext)
    family_id              UUID NOT NULL,           -- ←── rotation family
    expires_at             TIMESTAMPTZ NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL,
    revoked_at             TIMESTAMPTZ,             -- null if active
    replaced_by_token_id   UUID REFERENCES refresh_tokens(id),  -- null until rotated
    version                BIGINT NOT NULL DEFAULT 0
);
```

**Why HMAC-SHA256 instead of Argon2 for refresh tokens?**

Refresh tokens are already 256 bits of random — there's nothing for an attacker to "guess". The only adversary that matters is one who has read access to the database. HMAC with a server-side key means: a stolen DB dump alone doesn't yield usable tokens (the attacker still needs the HMAC key). It's much faster than Argon2 (microseconds vs ~50 ms), which matters because every request that uses a refresh token verifies it.

### Rotation

Every successful refresh issues a **new** refresh token and marks the old one rotated:

```
RT-1 → /refresh → server validates RT-1
                  server issues RT-2 (same family as RT-1)
                  server marks RT-1.revoked_at, RT-1.replaced_by = RT-2.id
                  returns RT-2 to client
```

The client now has RT-2. Next refresh:

```
RT-2 → /refresh → server validates RT-2
                  issues RT-3, marks RT-2 rotated
                  ...
```

This forms a chain: `RT-1 → RT-2 → RT-3 → ...`, all sharing the same `family_id`.

### Theft detection

The reason for rotation isn't "freshness". It's so the server can **detect token theft**.

Imagine an attacker steals RT-1. Two timelines are possible:

**Timeline A — legit user goes first:**
```
legit:    RT-1 → /refresh → got RT-2
attacker: RT-1 → /refresh → server sees RT-1 is already rotated → REUSE!
```

**Timeline B — attacker goes first:**
```
attacker: RT-1 → /refresh → got RT-2
legit:    RT-1 → /refresh → server sees RT-1 is already rotated → REUSE!
```

In both timelines, **one of the two parties presents a token that has already been rotated**. The server can't tell which is the attacker. So it does the safe thing:

> **Revoke the entire family.** Both legit and attacker are forced to re-authenticate.

This is the canonical defence. The kit implements it as `RefreshTokenRepository.revokeFamily(familyId, now)`.

### The two-transaction subtlety

`RefreshSession` returns `Result.Err(RefreshTokenReuseDetected)` when it detects reuse. But the UoW rolls back on `Err` — so the family-revoke would be rolled back too.

The fix is to **split into two transactions**:

```java
// Transaction 1: detect reuse + revoke. Returns Ok(reuseDetected?) so it commits.
var reuseSignal = uow.inTransaction(() -> {
    var stored = refreshTokens.findByTokenHash(presentedHash);
    if (stored.isPresent() && stored.get().isRotated()) {
        refreshTokens.revokeFamily(stored.get().familyId(), clock.instant());
        return Result.ok(Optional.of(theftSignal));
    }
    return Result.ok(Optional.empty());
}).orElseThrow();

if (reuseSignal.isPresent()) {
    audit.refreshReuseDetected(...);
    return Result.err(new AuthError.RefreshTokenReuseDetected());
}

// Transaction 2: normal rotation.
return uow.inTransaction(() -> { ... });
```

Cost: one extra DB round-trip on the (rare) theft-detection path. Benefit: the revoke actually commits.

---

## Role pickup on refresh

Refreshing **re-reads the user's current role** from the database and issues the new access token with that role. So if an admin demotes a user from `ADMIN` to `MEMBER`:

| When | Role in access token |
| --- | --- |
| Before demotion | `ADMIN` |
| Demotion happens | (user's currently-issued access token retains `ADMIN` for up to 15 min — its TTL) |
| User refreshes | New access token has `MEMBER` |

The 15-minute access-token TTL bounds the staleness window. A real-time demotion would need a token-revocation list — out of scope for the kit, but the hooks are there: just check `revoked_at` in `JwtBearerAuth.require`.

---

## Account states

`domain/src/main/java/myfluxo/domain/users/UserStatus.java` is a sealed type:

| State | Can register | Can login | Can refresh |
| --- | --- | --- | --- |
| `Pending` | (initial) | ✅ | ✅ |
| `Active` | — | ✅ | ✅ |
| `Deactivated` | — | ❌ `AccountInactive` | ❌ `AccountInactive` |

The kit ships `Pending` as the initial state because most real apps want a separate email-verification step. Promoting to `Active` is a project-specific use case you'll add (or you can remove the distinction and have `Register` create `Active` directly).

---

## Audit log

Every auth event flows through `AuthAuditLogger` (`application/src/main/java/myfluxo/application/auth/AuthAuditLogger.java`) on a dedicated SLF4J channel `myfluxo.audit.auth`. See [`docs/audit-log.md`](audit-log.md).

---

## Rate limiting

Login + register are protected by per-IP token buckets. See [`docs/rate-limiting.md`](rate-limiting.md).

---

## End-to-end proof

`adapter-persistence-jdbc/src/test/java/myfluxo/adapter/http/auth/AuthRoutesIT.java` boots the HTTP server with the production impls — real Argon2, real JWT, real Postgres — and exercises:

- `fullFlow_register_me_refresh_logout` — the canonical user journey
- `reusedRotatedRefreshToken_revokesFamily_returns401` — proves theft detection works end-to-end
- `login_wrongPassword_returns401InvalidCredentials` — proves wrong-password rejection
- `me_withoutBearer_returns401` — proves unauthenticated rejection
- `login_burst_eventuallyRateLimited` — proves the bucket trips

Run it with `mvn -pl adapter-persistence-jdbc verify`.

---

## See also

- [`docs/rbac.md`](rbac.md) — `Permission` × `Role` and `requirePermission`
- [`docs/rate-limiting.md`](rate-limiting.md) — Bucket4j wiring
- [`docs/audit-log.md`](audit-log.md) — the audit channel
- [`docs/result-and-errors.md`](result-and-errors.md) — `AuthError` as sealed sum type
