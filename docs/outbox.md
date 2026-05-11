# Transactional outbox

> Events that ride the same transaction as the data change that produced them. No "dual write" problem. No lost events. No phantom events.

`adapter-persistence-jdbc/.../outbox/JdbiOutboxDomainEventPublisher.java`
`adapter-persistence-jdbc/.../outbox/JdbiOutboxDispatcher.java`

---

## The problem it solves

A naïve "save the user, then publish a `UserRegistered` event" looks like:

```java
users.save(user);            // DB COMMIT
bus.publish(new UserRegistered(user.id()));  // network call to Kafka
```

Two failure modes, both real and both ugly:

1. **The COMMIT succeeds, the publish fails.** The user exists; downstream systems never hear about it. The welcome email never goes out.
2. **The publish succeeds, the COMMIT rolls back.** Downstream systems hear about a user that doesn't exist. Webhook subscribers retry forever.

This is called the **dual-write problem**. It is unsolvable in the general case without coordination (two-phase commit, distributed transactions, etc.), and those solutions are expensive and brittle.

The outbox pattern sidesteps it.

---

## The pattern

> "Don't publish the event. Write it to a table in the same transaction. A separate process publishes it later."

```
┌─────────────────── one Postgres transaction ───────────────────┐
│                                                                │
│  INSERT INTO users (...) VALUES (...)                          │
│  INSERT INTO outbox_events (id, event_type, payload, ...)      │
│                                                                │
│  COMMIT  ←─── both rows are either there, or both aren't        │
│                                                                │
└────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                ┌─── separate process / job ───┐
                │  SELECT ... FROM outbox_events│
                │   WHERE dispatched = FALSE    │
                │   FOR UPDATE SKIP LOCKED      │
                │  → forward to sink            │
                │  → mark dispatched            │
                └───────────────────────────────┘
```

Properties:

- **Atomicity.** The event row commits ↔ the data row commits. Same transaction.
- **At-least-once delivery.** The dispatcher retries until the sink succeeds. (Sinks must be idempotent — the outbox guarantees delivery, not exactly-once semantics.)
- **No distributed transactions.** Just one Postgres COMMIT.

---

## The publisher

`JdbiOutboxDomainEventPublisher` implements the `DomainEventPublisher` port:

```java
public void publish(DomainEvent event) {
    tx.useHandle(h -> h.createUpdate("""
            INSERT INTO outbox_events
                (id, event_type, payload, occurred_at,
                 dispatched, attempt_count)
            VALUES
                (:id, :eventType, CAST(:payload AS jsonb), :occurredAt,
                 FALSE, 0)
            """)
        .bind("id", UuidV7.generate())
        .bind("eventType", event.getClass().getName())
        .bind("payload", json.writeValueAsString(event))
        .bind("occurredAt", event.occurredAt())
        .execute());
}
```

Two things to notice:

1. **`tx.useHandle`** picks up the current UoW's transactional Handle ([docs/persistence.md](persistence.md)). The INSERT runs inside whatever transaction the use case opened — not a new one.
2. **The payload is `jsonb`.** Postgres stores it natively; subscribers can index, query, and project from it without any glue.

---

## The dispatcher

`JdbiOutboxDispatcher` runs on a schedule (cron, scheduled task, or a plain loop on a virtual thread — the class is deliberately scheduler-agnostic). Each tick:

```sql
SELECT id, event_type, payload, attempt_count
  FROM outbox_events
 WHERE dispatched = FALSE
 ORDER BY occurred_at
 LIMIT :batch
 FOR UPDATE SKIP LOCKED       ←── multi-instance safe
```

`FOR UPDATE SKIP LOCKED` is the key — it means **multiple dispatcher instances can run in parallel** without stepping on each other. Each instance grabs its own batch; rows being processed elsewhere are skipped.

For each row:

| Sink outcome | Dispatcher does |
| --- | --- |
| Sink returns normally | `UPDATE outbox_events SET dispatched = TRUE, dispatched_at = now(), attempt_count = attempt_count + 1` |
| Sink throws | `UPDATE outbox_events SET attempt_count = attempt_count + 1` — row stays pending, retried next tick |
| Payload fails to parse | Logged, attempt bumped; row stays pending |

The whole batch commits together at the end.

---

## The sink

`JdbiOutboxDispatcher` takes a `BiConsumer<String, JsonNode>` — `eventType` and the parsed payload tree. The kit doesn't pick what dispatch *means*. Common sinks:

| Sink | Effort |
| --- | --- |
| In-memory bus (subscribers within the same JVM) | Trivial; map eventType → subscriber |
| HTTP webhooks | Small adapter that POSTs the payload to subscribed URLs |
| Kafka producer | Add `kafka-clients`, map eventType → topic |
| Append to an event-log table | One SQL INSERT |

A typical wiring in `bootstrap`:

```java
new JdbiOutboxDispatcher(jdbi, (eventType, payload) -> {
    switch (eventType) {
        case "myfluxo.domain.users.events.UserRegistered" ->
            emailService.sendWelcome(payload.get("email").asText());
        // ...
    }
});
```

---

## What you don't get out of the box

Things the kit deliberately leaves to the project:

- **Scheduling.** Pick cron, a Helidon `Scheduled`, or a Quartz job — the dispatcher class is just a method to call.
- **Backoff strategy.** Each retry is on the same fixed schedule the dispatcher runs on. A real deployment usually wants exponential backoff on the row level — straightforward to add: store `next_attempt_at` and filter the SELECT.
- **Dead-letter handling.** Rows whose `attempt_count` exceeds a threshold should be moved to a quarantine table for human inspection. Not wired here; one SQL UPDATE away.
- **Exactly-once.** The outbox is at-least-once. Sinks must be idempotent (use the event `id` as the dedupe key downstream).

These are all small additions on top of the foundation.

---

## See also

- [`docs/persistence.md`](persistence.md) — how `TransactionalHandle` lets the publisher join the current UoW transaction
- [`docs/idempotency.md`](idempotency.md) — sibling pattern for HTTP-level deduplication
