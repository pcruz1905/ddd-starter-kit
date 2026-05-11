# java ddd starter-kit

Java 25 LTS DDD starter kit. Ports & adapters, sealed-type domain errors,
transactional outbox, HTTP-layer idempotency, audit-via-outbox.


---

## Stack

| Concern | Choice |
| --- | --- |
| Language | Java 25 LTS (records, sealed types, pattern matching, unnamed patterns, scoped values) |
| HTTP server | Helidon 4 SE (Níma, virtual-thread native) |
| Persistence | JDBI 3 + Postgres (no ORM, no codegen, no bytecode enhancement) |
| Migrations | Flyway 11 |
| Pool | HikariCP |
| DI | Avaje Inject (compile-time, no reflection) |
| Tests | JUnit 5 + AssertJ + Testcontainers Postgres |
| Build | Maven 3.9+ |

**No Spring. No Hibernate. No JOOQ.** Reasons documented in `docs/DDD_GAPS.md`.

---

## Module map

```
myfluxo/
├── kernel/                       DDD building blocks (no domain concepts)
│   ├── aggregate/                AggregateRoot, AbstractAggregateRoot, ArchivedSnapshot,
│   │                             EntityArchive, AggregateRestorer, OptimisticConcurrencyException
│   ├── event/                    DomainEvent, DomainEventPublisher
│   ├── result/                   Result<T,E>, DomainError
│   ├── id/                       Identifier<V>, UuidV7
│   ├── money/                    Money, CurrencyMismatchException
│   ├── idempotency/              IdempotencyKey
│   ├── pagination/               Page, PageRequest, SortDirection
│   ├── ddd/                      ValueObject, DomainService, Specification (markers)
│   └── util/                     ParallelFetch
├── domain/                       aggregates, value objects, sealed events/errors
│   ├── shared/model/             cross-feature VOs (Email, Money usage)
│   └── users/                    User aggregate, UserRepository port
├── application/                  use cases (orchestration only, no business logic)
│   ├── UnitOfWork.java
│   └── users/usecases/
├── adapter-http/                 Helidon HTTP routes + idempotency middleware
│   ├── HttpServer, ErrorHandlers, ErrorResponse, HttpJsonMapper
│   ├── idempotency/              IdempotencyCache port + IdempotencyMiddleware
│   └── users/                    UserRoutes, RegisterUserRequest, UserDto
├── adapter-persistence-jdbc/     the ONE persistence backend (Postgres via JDBI)
│   ├── JdbiSetup, JdbcDataSourceFactory, TransactionalHandle, JdbiUnitOfWork
│   ├── JdbiEntityArchive, JdbiIdempotencyCache, JsonMapper
│   ├── users/                    JdbiUserRepository, UserRowMapper, UserStatusMixin,
│   │                             UserRestorer
│   ├── outbox/                   JdbiOutboxDomainEventPublisher, JdbiOutboxDispatcher,
│   │                             EntityArchiveSink
│   └── src/main/resources/db/migration/  Flyway V1..V6
├── bootstrap/                    composition root — AppFactory, Application
└── docs/                         DDD_GAPS.md (roadmap)
```

---

## Architecture

Standard ports-and-adapters / hexagonal:

- **`domain/`** depends on `kernel/` only. No imports of any adapter,
  framework, or persistence concept. Aggregates own their invariants.
- **`application/`** depends on `domain/`. Orchestrates use cases via the
  `UnitOfWork` port and domain repositories. Returns `Result<T, E>` —
  errors are values, not thrown.
- **`adapter-*`** modules implement the ports. The HTTP adapter speaks
  Helidon; the JDBI adapter speaks Postgres. Neither knows the other.
- **`bootstrap/`** is the ONLY place where adapters are picked and
  wired. Everything else is `@Singleton` + constructor injection via
  Avaje Inject.

---

## Getting started

Prerequisites:
- JDK 25
- Maven 3.9+
- Docker (only at test time — Testcontainers spins up Postgres)

### Build

```bash
mvn clean install                # compile + unit + integration tests
mvn clean install -DskipTests    # just compile/package
```

### Run locally

The bootstrap wires the JDBI adapter unconditionally — there is no
in-memory fallback. Provide a Postgres:

```bash
export MYFLUXO_JDBC_URL=jdbc:postgresql://localhost:5432/myfluxo
export MYFLUXO_DB_USER=postgres
export MYFLUXO_DB_PASSWORD=postgres
export MYFLUXO_HTTP_PORT=8080       # default

mvn -pl bootstrap exec:java -Dexec.mainClass=myfluxo.bootstrap.Application
```

Flyway migrations run at startup. The HTTP server starts on
`http://localhost:8080`.

### Tests

Integration tests boot a Testcontainers Postgres (one container per
JVM). To make the second `mvn verify` and beyond **skip** the ~5s
container boot, enable Testcontainers' reuse once:

```bash
echo 'testcontainers.reuse.enable=true' >> ~/.testcontainers.properties
```

Per-developer flag; CI always gets a fresh container.

---

## Try the API

```bash
# Register a user
curl -X POST http://localhost:8080/users \
  -H 'Content-Type: application/json' \
  -d '{ "email": "alice@example.com" }'

# Same request with an Idempotency-Key — middleware replays the
# cached response on retry, responds with `X-Idempotent-Replay: true`
curl -X POST http://localhost:8080/users \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: signup-abc-123' \
  -d '{ "email": "bob@example.com" }'

# Fetch by id
curl http://localhost:8080/users/<uuid>
```

---

## How to add a new aggregate

Recipe for a new bounded context (e.g. `Order`):

1. **`domain/orders/`** — `Order` aggregate root extending
   `AbstractAggregateRoot<OrderId>`, sealed `OrderEvent`
   (`Placed`, `Cancelled`, `Deleted`, …), sealed `OrderError`,
   port `OrderRepository`.
2. **`domain/shared/model/`** — drop in any cross-feature value
   objects (e.g. `Address`, `LineItem`) used by more than one aggregate.
3. **`application/orders/usecases/`** — use cases (`PlaceOrder`,
   `CancelOrder`, …). Input: a command record. Output:
   `Result<DomainType, OrderError>`.
4. **`adapter-persistence-jdbc/`** — `JdbiOrderRepository`,
   `OrderRowMapper` (register in `JdbiSetup`), Flyway migration
   `V7__create_orders.sql`. For sealed status types add an
   `OrderStatusMixin` (register in `JsonMapper`).
5. **`adapter-http/orders/`** — `OrderRoutes`, request/response DTOs.
   Wrap state-changing endpoints in `idempotency.run(req, res, handler)`
   to inherit Stripe-style idempotency for free.

`bootstrap/AppFactory` picks the new beans up automatically — Avaje
scans `@Singleton`s at compile time.

---

## Key patterns

### Transactional outbox

Every aggregate mutation that emits a domain event writes the event
to `outbox_events` in the same transaction as the aggregate.
`JdbiOutboxDispatcher` polls the table (`FOR UPDATE SKIP LOCKED`) and
hands events to a composable sink chain. No "commit but didn't publish"
window.

### Hard-delete with audit via outbox

When an aggregate needs to be removed entirely (GDPR erasure, expired
sessions, abandoned drafts), the use case publishes a
`XxxEvent$Deleted` event carrying a full snapshot and DELETEs the row
in the same transaction. `EntityArchiveSink` (an outbox consumer)
writes the snapshot to `entity_archive`. Recovery: read the archive
snapshot, `restorer.rehydrate(...)`, `repo.restore(user)`.

Behavioural "delete" (deactivate, cancel, archive-as-status) stays in
the domain as state transitions — never as a soft-delete flag.

### HTTP-layer idempotency (Stripe / IETF RFC)

State-changing endpoints wrap their handler in
`IdempotencyMiddleware.run(...)`. The middleware reads the
`Idempotency-Key` header, hashes the request body, and:

- cache hit + same hash → replays the cached `(status, body)` with
  `X-Idempotent-Replay: true`
- cache hit + **different** hash → `422 Unprocessable Content`
- cache miss → runs the handler, captures the response, stores it

Use cases are unaware of idempotency. Cached data is raw bytes — no
typed result, no Jackson generic erasure, no per-use-case boilerplate.

### Optimistic concurrency

Every aggregate carries a `version`. `UPDATE` includes
`WHERE version = :loadedVersion` and bumps to `loadedVersion + 1`.
Conflict → `OptimisticConcurrencyException`. The single bump rule
lives in each repository's `save()`; `markPersisted(newVersion)`
adopts the persisted value into the aggregate's in-memory state.

### Domain events on aggregates

Aggregates `recordEvent(new XxxEvent.Variant(...))` inside their
behavior methods. The use case drains them with
`aggregate.pullEvents()` **inside** the UoW and hands them to the
`DomainEventPublisher` — so the event row and the aggregate row
commit atomically.

---

## License

MIT.
