# Docs

Design and rationale for the DDD Starter Kit.

The top-level [`README`](../README.md) is the elevator pitch + quick start. This directory is the **why**.

---

## Recommended reading order

If you're evaluating the kit:

1. [`architecture.md`](architecture.md) — the four-ring layering and how the compiler enforces it
2. [`result-and-errors.md`](result-and-errors.md) — `Result<T, E>` + sealed domain errors
3. [`persistence.md`](persistence.md) — JDBI, `Table<R>`, `UnitOfWork`, `ScopedValue`
4. [`auth.md`](auth.md) — the auth stack end-to-end
5. [`schema-drift.md`](schema-drift.md) — the CI gate that catches records-vs-migrations drift

If you're forking the kit and starting to build:

1. [`adding-an-aggregate.md`](adding-an-aggregate.md) — the recipe
2. [`rbac.md`](rbac.md) — adding permissions
3. [`outbox.md`](outbox.md) — when to publish a domain event
4. [`idempotency.md`](idempotency.md) — when to wrap an endpoint

---

## By topic

### Foundations

| Doc | What it covers |
| --- | --- |
| [`architecture.md`](architecture.md) | DDD layers, ports & adapters, ArchUnit-enforced rules |
| [`result-and-errors.md`](result-and-errors.md) | `Result<T, E>`, sealed domain errors, exhaustive matching |
| [`persistence.md`](persistence.md) | JDBI, row records, `Table<R>`, `JdbiUnitOfWork`, `ScopedValue` |

### Security stack

| Doc | What it covers |
| --- | --- |
| [`auth.md`](auth.md) | Argon2id passwords, HS256 JWT, refresh token rotation, theft detection, timing-safe login |
| [`rbac.md`](rbac.md) | `Permission` value object, sealed `Role` hierarchy, HTTP-boundary enforcement |
| [`rate-limiting.md`](rate-limiting.md) | Bucket4j per-IP token buckets |
| [`audit-log.md`](audit-log.md) | Dedicated SLF4J channel for security events |

### Reliability patterns

| Doc | What it covers |
| --- | --- |
| [`outbox.md`](outbox.md) | Transactional outbox + dispatcher (no dual-write problem) |
| [`idempotency.md`](idempotency.md) | Stripe / IETF `Idempotency-Key` middleware |
| [`schema-drift.md`](schema-drift.md) | CI check that fails when row records and migrations disagree |

### Recipes

| Doc | What it covers |
| --- | --- |
| [`adding-an-aggregate.md`](adding-an-aggregate.md) | End-to-end walkthrough of a new aggregate |

---

## What's in the source tree

Cross-reference of doc → primary source files:

| Doc | Primary code |
| --- | --- |
| `architecture.md` | `bootstrap/src/test/.../ArchitectureTest.java` |
| `result-and-errors.md` | `kernel/.../result/Result.java`, `domain/.../auth/errors/AuthError.java` |
| `persistence.md` | `adapter-persistence-jdbc/.../Table.java`, `JdbiUnitOfWork.java`, `RecordSql.java` |
| `auth.md` | `domain/.../auth/`, `application/.../auth/usecases/`, `adapter-http/.../auth/`, `adapter-auth/.../` |
| `rbac.md` | `domain/.../auth/model/Permission.java`, `Role.java`, `adapter-http/.../auth/JwtBearerAuth.java` |
| `rate-limiting.md` | `adapter-http/.../auth/AuthRateLimiter.java` |
| `audit-log.md` | `application/.../auth/AuthAuditLogger.java` |
| `outbox.md` | `adapter-persistence-jdbc/.../outbox/JdbiOutboxDomainEventPublisher.java`, `JdbiOutboxDispatcher.java` |
| `idempotency.md` | `adapter-http/.../idempotency/IdempotencyMiddleware.java` |
| `schema-drift.md` | `adapter-persistence-jdbc/src/test/.../SchemaDriftIT.java` |
