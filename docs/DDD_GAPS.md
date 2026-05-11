# DDD Gap Analysis

What myfluxo currently has from the DDD playbook, what's missing, and the
recommended order to fill the gaps. Written 2026-05-11.

This is a **living doc** — update it whenever a gap is closed or a new
one is identified.

---

## ✅ Already in place

| Pattern | Where |
| --- | --- |
| Aggregate Root | `kernel/AbstractAggregateRoot.java` |
| Entity | `domain/users/User.java` |
| Value Object | `domain/users/{Email,UserId,UserStatus}.java`, `kernel/{IdempotencyKey,ArchivedSnapshot,ValueObject}.java` |
| Repository | `domain/users/UserRepository.java` |
| Static Factory | `User.register`, `User.rehydrate` |
| Domain Event | `kernel/DomainEvent.java` + `domain/users/UserEvent.java` (sealed: Registered, Activated, Deactivated, EmailChanged, Deleted) |
| Domain Event Publisher | `kernel/DomainEventPublisher.java` + transactional outbox |
| Specification | `kernel/Specification.java` |
| Domain Error / Typed Result | `kernel/{DomainError,Result}.java`, `domain/users/UserError.java` |
| Optimistic Concurrency | `version` + parameterized `markPersisted(long)` |
| Unit of Work | `application/UnitOfWork.java` + ScopedValue-based `JdbiUnitOfWork` |
| Identifier / Strongly-typed ID | `kernel/Identifier.java` + branded records, `kernel/UuidV7.java` |
| Idempotency (HTTP layer, Stripe / IETF style) | `kernel/idempotency/IdempotencyKey.java` (value object) + `adapter-http/idempotency/{IdempotencyCache,IdempotencyMiddleware,CachedResponse,HttpResult}.java` + `adapter-persistence-jdbc/JdbiIdempotencyCache.java` |
| Pagination primitives | `kernel/{Page,PageRequest,SortDirection}.java` |
| Domain Service marker | `kernel/DomainService.java` |
| Application Service / Use Case | `application/users/RegisterUser.java` |
| Transactional Outbox | `outbox_events` + `JdbiOutboxDispatcher` + composable sinks |
| Audit / Archive / Restore | `entity_archive` + `kernel/EntityArchive.java` + `kernel/AggregateRestorer.java` + `repo.restore(...)` |
| Parallel Fetch helper | `kernel/ParallelFetch.java` |
| Money / Currency | `kernel/Money.java` + `kernel/CurrencyMismatchException.java` (uses `java.util.Currency`) |
| `UseCase<C, R, E>` interface | `application/UseCase.java` |
| Process Manager / Saga skeleton | `kernel/process/{ProcessInstanceId,ProcessInstance,ProcessHandler,ProcessInstanceRepository}.java` + `adapter-persistence-jdbc/process/JdbiProcessInstanceRepository.java` + V8 migration |
| Architecture tests (ArchUnit) | `bootstrap/src/test/.../ArchitectureTest.java` — 8 layering rules |
| Auditable aggregate mixin (opt-in) | `kernel/aggregate/AuditableAggregateRoot.java` (with `touch(Instant)` enforcing monotonic updatedAt) |
| Typed event subscribers | `kernel/event/EventSubscriber.java` + `adapter-persistence-jdbc/outbox/TypedSubscriberSink.java` |
| Background job runner (sellhub async-channel pattern in Java) | `kernel/job/{Job,JobScheduler,JobInstanceId}.java` + `adapter-persistence-jdbc/job/{JdbiJobScheduler,JdbiJobRunner}.java` + `V10` migration |

Roughly 18 of the 20 standard tactical-DDD building blocks. This is an
unusually complete foundation for a starter kit.

---

## ❌ Gaps, ranked by need for a Saleor-competitor

### 1. ~~Money / Currency value object~~ — **DONE 2026-05-11**

Built as `kernel/Money.java` + `kernel/CurrencyMismatchException.java`.
Uses `java.util.Currency` (built-in to JDK — knows all ISO 4217 codes
plus `getDefaultFractionDigits()`).

Design choices vs Sellhub's `Fiat`:
- Internal storage: `long minorUnits` instead of `BigDecimal` value in
  base units. Matches DB `BIGINT` columns, faster arithmetic, plenty of
  range (~9 × 10^18 minor units).
- Type-safety: cross-currency arithmetic throws
  `CurrencyMismatchException` at runtime (Java erases generics, so
  Sellhub's `Fiat<C extends CurrencyCode>` compile-time check isn't
  available in Java without phantom types).
- `BigDecimal` still used internally for `times(BigDecimal)` (tax
  rates) and `dividedBy(long)` (splits), with `HALF_EVEN` rounding back
  to currency scale.
- 33 unit tests cover construction, arithmetic, predicates, conversion,
  equality, overflow, rounding, and currency-mismatch.

### 2. ~~Process Manager / Saga pattern~~ — **DONE 2026-05-11 (skeleton)**

Kernel-level abstractions in place; no concrete process yet (`CheckoutProcess`,
`RefundProcess` etc. are added when their workflows exist).

What's there:
- `kernel/process/ProcessInstanceId.java` — UUID v7 branded id.
- `kernel/process/ProcessInstance.java` — persisted row: `id`,
  `processType`, `correlationKey`, `status` (`RUNNING|COMPLETED|FAILED`),
  opaque `state` JSON, `version`, timestamps. Lifecycle helpers:
  `start`, `advanced`, `completed`, `failed`.
- `kernel/process/ProcessHandler.java` — concrete-process contract.
  `onEvent(instance, event) → Optional<Advancement<S>>`. Generic state
  type the handler deserialises.
- `kernel/process/ProcessInstanceRepository.java` — port.
- `adapter-persistence-jdbc/process/JdbiProcessInstanceRepository.java` —
  Postgres impl, optimistic-concurrency via `WHERE version = N`.
- `V8__create_process_instances.sql` — table with a partial unique
  index enforcing "at most one RUNNING instance per
  (processType, correlationKey)", and a separate index for dispatcher
  queries.
- `JdbiProcessInstanceRepositoryIT.java` — 6 ITs covering save,
  advance, optimistic-concurrency, terminal states reopening,
  bounded listing.

To add a concrete process: implement `ProcessHandler<MyState>`, define
the state record, register the handler in a `ProcessDispatcher` (not
yet built — add when the first real workflow lands).

### 3. Anti-Corruption Layer (ACL) example — **small (one worked example)**

Stripe, email providers, shipping carriers — external systems have their
own models. ACL prevents their concepts from leaking into the domain.

Plan:
- One worked example (e.g., a `StripePaymentGateway` adapter) showing
  the Facade + Translator + Adapter triad.
- The domain only sees a clean `PaymentGateway` port returning typed
  domain results.
- Document the convention so future integrations follow it.

### 4. ~~`UseCase<C, R, E>` interface~~ — **DONE 2026-05-11**

Defined in `application/UseCase.java`:

```java
public interface UseCase<C, R, E> {
    Result<R, E> handle(C command);
}
```

`RegisterUser` now `implements UseCase<RegisterUserCommand, User, UserError>`.
Every new use case follows the same shape — adapters can dispatch them
uniformly and middleware can wrap them generically.

### 5. ~~Architecture tests (ArchUnit)~~ — **DONE 2026-05-11**

`bootstrap/src/test/java/myfluxo/bootstrap/architecture/ArchitectureTest.java`
— 7 rules running on every `mvn test`:

1. `domain` does not depend on any adapter / bootstrap.
2. `domain` does not depend on `application`.
3. `application` does not depend on any adapter / bootstrap.
4. `kernel` has no framework imports (Spring, Helidon, JDBI, Jackson,
   Avaje, JPA, Flyway, Hikari).
5. `kernel` does not depend on domain / application / adapters.
6. `domain` has no framework imports (the same exclusion list plus
   `jakarta.inject`).
7. `adapter-http` does not depend on `adapter-persistence-*`. The
   reverse direction (persistence → http to implement the
   `IdempotencyCache` port) is the legitimate one.

ArchUnit gates fail the build at CI time — no more architectural
drift slipping through PR review.

### 6. Typed event subscriber pattern — **small**

The outbox dispatcher hands events to `BiConsumer<String, JsonNode>` —
fine for sinks (logger, archive, Kafka producer). But for domain logic
("when a user registers, send a welcome email") there's no typed
`EventSubscriber<UserEvent.Registered>` pattern.

Plan:
- Typed `EventSubscriber<E extends DomainEvent>` interface.
- An in-process bus that pattern-matches on event types and dispatches
  to typed subscribers.
- The bus consumes deserialized typed events from the outbox or from an
  in-process publisher.

### 7. ~~Materialized read views (light CQRS)~~ — **removed by design 2026-05-11**

Decision: this kit is a single-Postgres, in-process app. The right
posture is *synchronous denormalisation when needed* — same UoW as the
aggregate save, never drifts. That's just a second table with an index,
not a pattern that needs an abstraction. The earlier `Projection`
marker and `docs/CQRS.md` were deleted; the only legitimate use of
async projections (different storage tech, external consumers, decoupled
failure) is far enough away to defer.

If a real reporting/search workload appears later, revisit with a
worked example (Elasticsearch, ClickHouse, Kafka consumer) — until then,
write the SQL.

### 8. ~~Clock abstraction passed in~~ — **resolved by discipline 2026-05-11**

Verified the current state: `Clock` is injected wherever **business
time** matters (e.g. `RegisterUser` uses `clock.instant()` for the
user's `createdAt`). Remaining `Instant.now()` calls are all
infrastructure-internal timestamps where deterministic time is not
useful in tests:

| Site | What | Tests need to freeze it? |
| --- | --- | --- |
| `JdbiIdempotencyCache` (`now`, `expires_at`) | Cache row TTL | No — tests assert "row exists with status N", not exact times |
| `JdbiOutboxDispatcher.dispatched_at` | Dispatch attempt timestamp | No — pure forensics |
| `EntityArchiveSink.archived_at` | Archive write timestamp | No — pure forensics |

Rule going forward: any `Instant.now()` that affects business semantics
or a test assertion goes through an injected `Clock`. Infrastructure
write-timestamps stay on the wall clock.

### 9. Audit timestamp mixin — **small**

`createdAt` is per-aggregate. For a Saleor-scale kit, an
`AuditableAggregateRoot extends AbstractAggregateRoot` with `createdAt`
+ `updatedAt` would reduce boilerplate.

**Skip if you prefer the DDD-pure path** of declaring audit fields per
aggregate. Decision logged in the user → `markPersisted(long)` refactor:
we lean DDD-pure for this kit, but the mixin is an escape hatch for
aggregates that genuinely want it.

### 10. Ubiquitous language doc — **very small**

`docs/GLOSSARY.md` capturing the terms your code uses. DDD calls this
the "ubiquitous language" — it's strategic, not code.

Initial entries to capture (from current code):
- **User** — a registered participant in the system.
- **User Status** — Pending (awaits activation), Active, Deactivated
  (manual offboarding, reason recorded).
- **Idempotency Key** — client-supplied dedupe token for retryable
  operations.
- **Outbox Event** — durable domain event awaiting external dispatch.
- **Entity Archive** — snapshot of a hard-deleted aggregate, kept for
  recovery and audit.
- **Optimistic Concurrency Version** — monotonic counter on every
  aggregate row, advanced on every successful save.

---

## Recommended next batch (in order)

Items 1, 4, 5 are done (Money, UseCase, ArchUnit). Item 2 (Process
Manager skeleton) is done. Remaining items by priority:

1. **Anti-Corruption Layer (ACL) example** (#3 above) — needed when
   integrating Stripe / email providers / shipping carriers. Worked
   example pulls a payment gateway in cleanly.
2. **Typed event subscriber pattern** (#6) — promote the outbox sink's
   `BiConsumer<String, JsonNode>` to a typed `EventSubscriber<E>` for
   domain-level handlers ("when a user registers, send welcome email").
3. **Ubiquitous language doc** (#10) — `docs/GLOSSARY.md` capturing
   the terms in current and future code.

Deferred: #9 (audit-timestamp mixin — only if we end up repeating
createdAt/updatedAt boilerplate across aggregates). #7 (CQRS read views)
was removed from scope; see its entry.

Items #6 (typed subscribers), #8 (clock injection), and #9 (audit mixin)
are small fix-up tasks that can happen anytime.

Item #10 (glossary) is a deferred documentation task.

---

## Things we deliberately do NOT have (and probably shouldn't)

These are sometimes called "missing" by DDD purists but are wrong fits
for a starter kit:

| Pattern | Why we skip |
| --- | --- |
| Heavy DI framework (Spring, Guice) | We use Avaje Inject (compile-time, no runtime reflection). |
| JPA / Hibernate | We chose JDBI — declarative SQL, no codegen, no bytecode enhancement. |
| Soft-delete via `deleted_at` flag on every table | Replaced with the `entity_archive` table + outbox sink. Cleaner queries, no filter foot-guns. |
| Event sourcing as the persistence model | Snapshot-only persistence; the outbox carries events as side-effects, not as the source of truth. Documented in CodeOpinion's "snapshots are optimization, not replacement" guidance. |
| Full CQRS (separate write/read stores) | Premature. Synchronous denormalisation in the same UoW covers our cases. Revisit only when storage tech for reads must differ. |

---

## References

- [DDD Part 2: Tactical Domain-Driven Design — Vaadin](https://vaadin.com/blog/ddd-part-2-tactical-domain-driven-design)
- [Implementing DDD Building Blocks in Java — Oliver Drotbohm](http://odrotbohm.de/2020/03/Implementing-DDD-Building-Blocks-in-Java/)
- [Domain-Driven Design Reference — Eric Evans](https://www.domainlanguage.com/wp-content/uploads/2016/05/DDD_Reference_2015-03.pdf)
- [Bounded Context — Martin Fowler](https://martinfowler.com/bliki/BoundedContext.html)
- [DDD Strategic Patterns — Open Group](https://pubs.opengroup.org/architecture/o-aa-standard/DDD-strategic-patterns.html)
- [Application Services vs Domain Services — Vladimir Khorikov](https://enterprisecraftsmanship.com/posts/domain-vs-application-services/)
- [Process Managers and Sagas in DDD — Hossein Nejati (Mar 2026)](https://hosseinnejati.medium.com/process-managers-and-sagas-in-ddd-coordinating-long-running-workflows-61193672ad5c)
- [Saga and Process Manager — Event-Driven.io](https://event-driven.io/en/saga_process_manager_distributed_transactions/)
- [Anti-Corruption Layer Pattern in Java — java-design-patterns](https://java-design-patterns.com/patterns/anti-corruption-layer/)
- [CQRS — Martin Fowler](https://martinfowler.com/bliki/CQRS.html)
- [Snapshots in Event Sourcing for Rehydrating Aggregates — CodeOpinion](https://codeopinion.com/snapshots-in-event-sourcing-for-rehydrating-aggregates/)
