<h1 align="center">DDD Starter Kit</h1>

<p align="center">
  Production-grade backend foundation in <strong>Java 25 LTS</strong>.<br/>
  Domain-Driven Design · Ports & Adapters · Built to be forked.
</p>

<p align="center">
  <a href="#stack"><img alt="Java 25" src="https://img.shields.io/badge/Java-25_LTS-orange?logo=openjdk"></a>
  <a href="#stack"><img alt="Helidon 4" src="https://img.shields.io/badge/Helidon-4_(N%C3%ADma)-blue"></a>
  <a href="#stack"><img alt="Postgres" src="https://img.shields.io/badge/Postgres-supported-336791?logo=postgresql"></a>
  <a href="LICENSE"><img alt="MIT" src="https://img.shields.io/badge/License-MIT-green"></a>
</p>

<p align="center">
  <a href="#what-you-get">Features</a> ·
  <a href="#quick-start">Quick start</a> ·
  <a href="docs/README.md">Docs</a> ·
  <a href="#auth-api">Auth API</a> ·
  <a href="docs/adding-an-aggregate.md">Add an aggregate</a>
</p>

---

This is a **starter kit**, not a framework. Fork it, replace the example `User` aggregate with your own, and you have a backend with auth, RBAC, refresh-token rotation with theft detection, rate limiting, audit logging, transactional outbox, HTTP idempotency, and a CI-enforced schema-drift gate.

The kit is opinionated — every choice has a rationale in [`docs/`](docs/). If you disagree with one, swap that piece; everything sits behind a port.

---

## What you get

- **DDD building blocks** — aggregates, value objects, repositories, domain events, sealed errors. Compiler-enforced layering via ArchUnit. [`docs/architecture.md`](docs/architecture.md)
- **`Result<T, E>` everywhere** — failures are values, not exceptions; pattern-match exhaustively. [`docs/result-and-errors.md`](docs/result-and-errors.md)
- **JDBI 3 + Postgres** — declarative SQL, no codegen, no ORM. `Table<R>` derives the boilerplate. [`docs/persistence.md`](docs/persistence.md)
- **Java 25 `ScopedValue`-based UnitOfWork** — no ThreadLocal, no `@Transactional` annotations, commit/rollback driven by `Result`. [`docs/persistence.md`](docs/persistence.md#unit-of-work)
- **Schema drift CI gate** — fails the build when row records and Flyway migrations stop agreeing. [`docs/schema-drift.md`](docs/schema-drift.md)
- **Full auth stack** — register / login / refresh-with-rotation / logout / change-password. [`docs/auth.md`](docs/auth.md)
- **OWASP-grade password hashing** — Argon2id with recommended parameters, timing-attack-safe login. [`docs/auth.md#password-hashing--argon2id`](docs/auth.md#password-hashing--argon2id)
- **Refresh-token rotation with theft detection** — family revocation when a rotated token is replayed. [`docs/auth.md#refresh-tokens--rotation--family-revocation`](docs/auth.md#refresh-tokens--rotation--family-revocation)
- **RBAC** — `Permission` × sealed `Role` hierarchy, HTTP-boundary `requirePermission`. [`docs/rbac.md`](docs/rbac.md)
- **Brute-force protection** — per-IP Bucket4j token buckets on `/login` and `/register`. [`docs/rate-limiting.md`](docs/rate-limiting.md)
- **Structured audit log** — dedicated SLF4J channel for security events. [`docs/audit-log.md`](docs/audit-log.md)
- **Transactional outbox** — events ride the same transaction as the data change. `FOR UPDATE SKIP LOCKED` dispatcher. [`docs/outbox.md`](docs/outbox.md)
- **HTTP idempotency middleware** — Stripe / IETF `Idempotency-Key` semantics. [`docs/idempotency.md`](docs/idempotency.md)
- **End-to-end ITs** — Testcontainers Postgres, real HTTP server, no mocks at the integration layer.

---

## Stack

| Concern | Choice | Why |
| --- | --- | --- |
| Language | **Java 25 LTS** | Records, sealed types, pattern matching, scoped values, unnamed patterns |
| HTTP | **Helidon 4 SE (Níma)** | Virtual-thread-native, no Servlet API, no Spring |
| Persistence | **JDBI 3** + Postgres | Declarative SQL, no codegen, no bytecode magic |
| Migrations | **Flyway 11** | Versioned, checksummed, drift-checked in CI |
| Pool | HikariCP | Standard |
| DI | **Avaje Inject** | Compile-time, no reflection |
| Passwords | **Password4j Argon2id** | OWASP recommended |
| JWT | **jjwt 0.12** | HS256 access tokens |
| Rate limit | **Bucket4j 8** | Per-IP token buckets |
| Tests | JUnit 5 · AssertJ · Testcontainers · ArchUnit | Real DB, no mocks at integration |
| Build | Maven 3.9+ | Multi-module reactor |
| CI | GitHub Actions | Build · drift check · ArchUnit · E2E |

**No Spring. No Hibernate. No JOOQ codegen.** See the rationale in [`docs/architecture.md#why-this-stack`](docs/architecture.md#why-this-stack).

---

## Quick start

Requires **JDK 25** + Docker (for Testcontainers).

```bash
# 1. Generate two distinct 32-byte secrets
export MYFLUXO_JWT_SECRET=$(openssl rand -hex 32)
export MYFLUXO_REFRESH_TOKEN_SECRET=$(openssl rand -hex 32)

# 2. Point at your Postgres
export MYFLUXO_JDBC_URL='jdbc:postgresql://localhost:5432/myfluxo'
export MYFLUXO_DB_USER='myfluxo'
export MYFLUXO_DB_PASSWORD='...'

# 3. Build + run the full test suite (unit, IT, schema-drift, ArchUnit)
mvn verify

# 4. Run the app
mvn -pl bootstrap exec:java -Dexec.mainClass=myfluxo.bootstrap.Application
```

The app listens on `MYFLUXO_HTTP_PORT` (default `8080`).

---

## Architecture at a glance

Four concentric rings. Dependencies point inward. The compiler enforces it.

```
┌──────────────────────────────────────────────────────────┐
│  bootstrap            (composition root, main, wiring)   │
│ ┌──────────────────────────────────────────────────────┐ │
│ │  adapters         (HTTP, JDBC, auth-crypto)          │ │
│ │ ┌──────────────────────────────────────────────────┐ │ │
│ │ │  application  (use cases, UnitOfWork)            │ │ │
│ │ │ ┌──────────────────────────────────────────────┐ │ │ │
│ │ │ │  domain  (aggregates, value objects, errors) │ │ │ │
│ │ │ │ ┌────────────────────────────────────────────┐ │ │ │
│ │ │ │ │  kernel  (Result, Identifier, DomainEvent) │ │ │ │
│ │ │ │ └────────────────────────────────────────────┘ │ │ │
│ │ │ └──────────────────────────────────────────────┘ │ │ │
│ │ └──────────────────────────────────────────────────┘ │ │
│ └──────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
```

Layering rules are enforced by ArchUnit in CI — see [`docs/architecture.md`](docs/architecture.md).

---

## Module map

```
.
├── kernel/                       DDD building blocks (Result, Identifier, AggregateRoot, ...)
├── domain/                       Aggregates, value objects, sealed errors, ports
│   ├── users/                       User aggregate
│   └── auth/                        Credentials + RefreshToken aggregates, Role, Permission
├── application/                  Use cases (orchestration), UnitOfWork port
│   ├── users/usecases/              RegisterUser
│   └── auth/usecases/               Register, Login, RefreshSession, Logout, ChangePassword
├── adapter-persistence-jdbc/     JDBI repositories + Postgres
│   ├── outbox/                      Transactional outbox + dispatcher
│   └── ...
├── adapter-http/                 Helidon routes + middlewares
│   ├── auth/                        AuthRoutes, JwtBearerAuth, AuthRateLimiter
│   └── idempotency/                 IdempotencyMiddleware
├── adapter-auth/                 Argon2 hasher, JWT issuer, HMAC refresh strategy
├── bootstrap/                    Composition root (AppFactory + Application main, ArchUnit tests)
└── docs/                         Design rationale ← start here for the "why"
```

Layering rules — `domain` depends only on `kernel`; `application` on `kernel` + `domain`; no sibling-adapter coupling — are enforced by ArchUnit. Break a rule and CI breaks. See [`docs/architecture.md`](docs/architecture.md).

---

## Auth API

All endpoints under `/v1/auth/*`. JSON bodies. Errors in Stripe-shape `{"error": {"code": "...", "message": "..."}}`. Full design rationale in [`docs/auth.md`](docs/auth.md).

| Method | Path | Auth | Body | Returns |
| --- | --- | --- | --- | --- |
| `POST` | `/v1/auth/register` | — | `{email, password}` | `201` session |
| `POST` | `/v1/auth/login` | — | `{email, password}` | `200` session |
| `POST` | `/v1/auth/refresh` | — | `{refreshToken}` | `200` rotated session |
| `POST` | `/v1/auth/logout` | — | `{refreshToken}` | `204` |
| `POST` | `/v1/auth/change-password` | Bearer | `{oldPassword, newPassword}` | `204` |
| `GET`  | `/v1/auth/me` | Bearer | — | `200 {userId, role}` |

Session shape:

```json
{
  "userId": "uuid",
  "role": "ADMIN" | "MEMBER" | "VIEWER",
  "accessToken": "<jwt>",
  "accessTokenExpiresAt": "iso-instant",
  "refreshToken": "<opaque>",
  "refreshTokenExpiresAt": "iso-instant"
}
```

Security properties (all proven by tests):

- Argon2id password hashing (OWASP parameters: 19 MiB · 2 iter · 1 par)
- Timing-attack-safe login (decoy hash always verified)
- Refresh-token rotation with family-based theft detection
- HMAC-SHA256 server-side hashing of refresh tokens
- Rate limiting (5 login / 15 min, 3 register / hour, per IP)
- Audit log on dedicated SLF4J channel `myfluxo.audit.auth`

---

## Configuration

| Variable | Required | Default | Notes |
| --- | --- | --- | --- |
| `MYFLUXO_JDBC_URL` | ✅ | — | Postgres JDBC URL |
| `MYFLUXO_DB_USER` | ✅ | — | |
| `MYFLUXO_DB_PASSWORD` | ✅ | — | |
| `MYFLUXO_JWT_SECRET` | ✅ | — | ≥ 32 bytes; `openssl rand -hex 32` |
| `MYFLUXO_REFRESH_TOKEN_SECRET` | ✅ | — | ≥ 32 bytes; **distinct** from `JWT_SECRET` |
| `MYFLUXO_JWT_ISSUER` | — | `myfluxo` | `iss` claim |
| `MYFLUXO_ACCESS_TOKEN_TTL_MINUTES` | — | `15` | Access JWT lifetime |
| `MYFLUXO_REFRESH_TOKEN_TTL_DAYS` | — | `7` | Refresh-token lifetime |
| `MYFLUXO_HTTP_PORT` | — | `8080` | HTTP listen port |

---

## Documentation

Start with [`docs/README.md`](docs/README.md) — it has a recommended reading order and topic index. Highlights:

### Foundations
- [Architecture](docs/architecture.md) — the four rings, ports & adapters, ArchUnit
- [Result & errors](docs/result-and-errors.md) — sealed sums, exhaustive matching
- [Persistence](docs/persistence.md) — JDBI, `Table<R>`, UnitOfWork, ScopedValue

### Security
- [Auth](docs/auth.md) — Argon2id, JWT, rotation, theft detection, timing-safe login
- [RBAC](docs/rbac.md) — Permission × Role
- [Rate limiting](docs/rate-limiting.md) — Bucket4j
- [Audit log](docs/audit-log.md) — dedicated SLF4J channel

### Reliability patterns
- [Outbox](docs/outbox.md) — transactional events without dual-write
- [Idempotency](docs/idempotency.md) — Stripe / IETF middleware
- [Schema drift](docs/schema-drift.md) — CI gate against records-vs-migrations drift

### Recipes
- [Adding an aggregate](docs/adding-an-aggregate.md) — end-to-end recipe

---

## Why no Spring / Hibernate / JOOQ codegen

| Tool | Why we skip |
| --- | --- |
| Spring | Reflection + classpath magic; Avaje Inject (compile-time, no reflection) instead |
| Hibernate / JPA | ORM impedance mismatch; JDBI's declarative SQL is the better default for a starter |
| JOOQ codegen | Adds a build-time DB or DDL-parsing step; `Table<R>` covers ~90% of the ergonomic gap at ~10% of the build cost |

Long version: [`docs/architecture.md#why-this-stack`](docs/architecture.md#why-this-stack), [`docs/persistence.md#why-this-and-not-jooq-codegen`](docs/persistence.md#why-this-and-not-jooq-codegen).

---

## License

[MIT](LICENSE). Fork it, ship it.
