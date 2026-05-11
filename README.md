# DDD Starter Kit — Java 25 backend

Production-grade backend starter kit for greenfield projects. Domain-Driven Design,
ports & adapters, sealed-type domain errors, transactional outbox, HTTP-layer
idempotency, full auth stack (Argon2id + JWT + rotating refresh tokens), Bucket4j
rate limiting, structured audit logging, schema-drift CI gate.

**Built to be forked.** Replace the example `User` aggregate with your own; the
rest of the kit — auth, persistence wiring, HTTP routes, CI — comes for free.

---

## Stack

| Concern | Choice | Why |
| --- | --- | --- |
| Language | Java 25 LTS | Records, sealed types, pattern matching, unnamed patterns, scoped values |
| HTTP server | [Helidon 4 SE](https://helidon.io/) (Níma) | Virtual-thread-native, lean, no Spring |
| Persistence | [JDBI 3](https://jdbi.org/) + Postgres | Declarative SQL, no codegen, no bytecode magic |
| Migrations | [Flyway 11](https://flywaydb.org/) | Versioned, drift-checked in CI |
| Pool | HikariCP | Standard |
| DI | [Avaje Inject](https://avaje.io/inject/) | Compile-time, no reflection |
| Password hashing | [Password4j](https://password4j.com/) Argon2id | OWASP-recommended default |
| JWT | [jjwt 0.12](https://github.com/jwtk/jjwt) | HS256 access tokens |
| Rate limiting | [Bucket4j](https://github.com/bucket4j/bucket4j) | Token-bucket per IP |
| Tests | JUnit 5 + AssertJ + Testcontainers Postgres | Real DB in CI |
| Build | Maven 3.9+ | Multi-module reactor |
| CI | GitHub Actions | Build + drift check + E2E |

**No Spring. No Hibernate. No JOOQ codegen.**

---

## What you get out of the box

- ✅ DDD aggregates with optimistic concurrency
- ✅ Sealed-type domain errors (`Result<T, E>`, exhaustive at every call site)
- ✅ Transactional outbox + JSON-archived hard delete
- ✅ Functional Unit of Work (Java 25 `ScopedValue`-based, no ThreadLocal)
- ✅ Process Manager / Saga skeleton
- ✅ Background job runner (`FOR UPDATE SKIP LOCKED`)
- ✅ HTTP-layer idempotency middleware (Stripe / IETF style)
- ✅ Composable dynamic-query DSL (`Condition` + `Predicates`)
- ✅ Schema drift check in CI (records vs migrations)
- ✅ ArchUnit layering rules enforced in CI
- ✅ **Auth stack**: register / login / refresh-with-rotation / logout / change-password
- ✅ **RBAC**: `Permission` × `Role` (`Admin | Member | Viewer`), HTTP-level `requirePermission`
- ✅ **Brute-force protection**: per-IP rate limiting on `/login` and `/register`
- ✅ **Audit log**: dedicated SLF4J channel (`myfluxo.audit.auth`) for every auth event
- ✅ **Theft detection**: refresh-token family revocation on replayed/rotated tokens

---

## Module map

```
.
├── kernel/                       DDD building blocks (no domain concepts)
├── domain/                       aggregates, value objects, sealed events/errors
│   ├── users/                    User aggregate
│   └── auth/                     Credentials + RefreshToken aggregates, Role, Permission
├── application/                  use cases (orchestration only)
│   ├── users/usecases/           RegisterUser
│   └── auth/usecases/            Register, Login, RefreshSession, Logout, ChangePassword
├── adapter-persistence-jdbc/     JDBI + Postgres impls
│   ├── auth/                     Credentials + RefreshToken repos
│   ├── process/                  Process-instance repo
│   ├── users/                    User repo
│   └── outbox/                   Outbox dispatcher + sinks
├── adapter-http/                 Helidon routes + DTOs
│   ├── auth/                     AuthRoutes, JwtBearerAuth, AuthRateLimiter
│   └── users/                    UserRoutes
├── adapter-auth/                 Argon2 hasher, JWT issuer, HMAC refresh strategy
└── bootstrap/                    Composition root (AppFactory + Application main)
```

---

## Quick start

```bash
# 1. Set required env vars (generate secrets with: openssl rand -hex 32)
export MYFLUXO_JDBC_URL='jdbc:postgresql://localhost:5432/myfluxo'
export MYFLUXO_DB_USER='myfluxo'
export MYFLUXO_DB_PASSWORD='...'
export MYFLUXO_JWT_SECRET=$(openssl rand -hex 32)
export MYFLUXO_REFRESH_TOKEN_SECRET=$(openssl rand -hex 32)

# 2. Build + run all tests (needs Docker for Testcontainers)
mvn verify

# 3. Run the app
mvn -pl bootstrap exec:java -Dexec.mainClass=myfluxo.bootstrap.Application
```

App listens on `MYFLUXO_HTTP_PORT` (default 8080).

---

## Auth API

All endpoints under `/v1/auth/*`. JSON bodies. Errors in Stripe-shape
`{"error": {"code": "...", "message": "..."}}`.

| Method | Path | Auth | Body | Returns |
| --- | --- | --- | --- | --- |
| POST | `/v1/auth/register` | — | `{email, password}` | `201` session |
| POST | `/v1/auth/login` | — | `{email, password}` | `200` session |
| POST | `/v1/auth/refresh` | — | `{refreshToken}` | `200` rotated session |
| POST | `/v1/auth/logout` | — | `{refreshToken}` | `204` |
| POST | `/v1/auth/change-password` | Bearer | `{oldPassword, newPassword}` | `204` |
| GET | `/v1/auth/me` | Bearer | — | `200 {userId, role}` |

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

### Production-grade properties

- **Argon2id** password hashing with OWASP-recommended parameters (19 MiB, 2 iter, 1 par)
- **Timing-attack-safe login**: Argon2 verify runs against a decoy hash even when the user doesn't exist
- **Refresh token rotation** with family tracking — replayed token after rotation triggers revocation of the entire family (forces re-auth on every device)
- **HMAC-SHA256 server-side hashing** of refresh tokens (a DB dump alone doesn't yield usable tokens)
- **JWT signing** with HS256 and configurable issuer
- **Rate limiting**: 5 login / 15 min and 3 register / hour per IP
- **Audit log** for every auth event (register, login success/failure, refresh, logout, password change, theft detection) on a dedicated SLF4J channel

---

## Configuration (env)

| Var | Required | Default | Notes |
| --- | --- | --- | --- |
| `MYFLUXO_JDBC_URL` | ✅ | — | Postgres JDBC URL |
| `MYFLUXO_DB_USER` | ✅ | — | |
| `MYFLUXO_DB_PASSWORD` | ✅ | — | |
| `MYFLUXO_JWT_SECRET` | ✅ | — | ≥32 bytes (UTF-8); `openssl rand -hex 32` |
| `MYFLUXO_REFRESH_TOKEN_SECRET` | ✅ | — | ≥32 bytes; distinct from `JWT_SECRET` |
| `MYFLUXO_JWT_ISSUER` | — | `myfluxo` | `iss` claim on issued JWTs |
| `MYFLUXO_ACCESS_TOKEN_TTL_MINUTES` | — | `15` | Access JWT lifetime |
| `MYFLUXO_REFRESH_TOKEN_TTL_DAYS` | — | `7` | Refresh-token lifetime |
| `MYFLUXO_HTTP_PORT` | — | `8080` | HTTP listen port |

---

## Architecture cheat sheet

```
HTTP request
  └─ Routes (adapter-http)             — parse body, call use case
       └─ Use case (application)        — uow.inTransaction { ... }, returns Result<T,E>
            ├─ Domain                    — aggregates enforce invariants, record events
            └─ Ports (domain)            — UserRepository, CredentialsRepository, ...
                 └─ Adapters             — JDBI repos, Argon2 hasher, JWT issuer
                      └─ Postgres        — Flyway-migrated schema
                           └─ outbox     — dispatcher → archive / sink / Kafka / ...
```

Layering rules enforced by **ArchUnit** in `bootstrap/.../ArchitectureTest`:
- `domain` has no framework imports
- `domain` depends only on `kernel`
- `application` depends only on `kernel` + `domain`
- `kernel` has no framework imports
- No adapter depends on another adapter (one documented exception)

---

## Schema drift gate

`SchemaDriftIT` boots Postgres, runs Flyway, then cross-validates every
`Table<R>` row record against `information_schema.columns`. If a migration
renames a column without updating the row record (or vice versa), CI
fails with a precise diff. GitHub Actions workflow surfaces it as
**"Schema drift detected"** before any other test runs.

---

## Why no Spring / Hibernate / JOOQ codegen

| Tool | Why we skip |
| --- | --- |
| Spring | Reflection + classpath magic; we use Avaje Inject (compile-time, no reflection) |
| Hibernate / JPA | ORM impedance mismatch; we use JDBI for declarative SQL |
| JOOQ codegen | Adds a build-time DB or DDL-parsing step; the small `Table<R>` helper covers the ergonomic gap |

The kit's persistence ergonomics: snake_case ↔ camelCase auto-mapping via
JDBI's `ConstructorMapper`, `@ColumnName` overrides where needed,
`@JsonbColumn` for Postgres `jsonb`, `Table<R>` derivable SQL strings,
`col(...)` startup-time column-name validation. ~90% of the type-safety
of jOOQ codegen at ~10% of the build cost.

---

## Adding a new aggregate

1. New row record under `adapter-persistence-jdbc/.../yourthing/YourRow.java`
   with `public static final Table<YourRow> TABLE = Table.of("yourthings", YourRow.class);`
2. Flyway migration creating the `yourthings` table
3. Domain aggregate extends `AbstractAggregateRoot<YourId>`
4. Repository port in `domain.yourthing` + `JdbiYourRepository extends JdbiAggregateRepository<...>`
5. Register `YourRow.TABLE.rowMapperFactory()` in `JdbiSetup`
6. Add `YourRow.TABLE` to `SchemaDriftIT.REGISTERED_TABLES`
7. Use case in `application.yourthing.usecases`
8. HTTP route in `adapter-http.yourthing`

The first new aggregate takes a few hours (you have to look up the pattern).
The next takes ~30 minutes.

---

## License

MIT. Fork it, ship it.
