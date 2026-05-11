package myfluxo.adapter.persistence.jdbc.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import myfluxo.adapter.persistence.jdbc.JsonMapper;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Polls {@code outbox_events} for pending rows and hands them to a sink.
 *
 * <p>Run on a schedule — e.g., a cron job, a Helidon scheduled task,
 * or a plain loop on its own virtual thread. This class intentionally
 * doesn't pick a scheduler: that's a deployment decision.
 *
 * <p>The {@link #dispatchPending} method:
 * <ol>
 *     <li>Opens its own transaction (separate from any UoW).</li>
 *     <li>Selects up to {@code batchSize} pending rows
 *         {@code FOR UPDATE SKIP LOCKED} — multiple dispatcher
 *         instances can run in parallel without stepping on each other.</li>
 *     <li>For each row, calls {@code sink(eventType, payload)}. The sink
 *         decides what "dispatch" actually means: write to Kafka, POST
 *         to a webhook, log it, hand to an in-memory bus, etc.</li>
 *     <li>Marks each successfully dispatched row {@code dispatched = true},
 *         records {@code dispatched_at}, and bumps {@code attempt_count}.</li>
 *     <li>If the sink throws, bumps {@code attempt_count} only — the row
 *         stays pending and is retried on the next poll.</li>
 *     <li>Commits the whole batch.</li>
 * </ol>
 *
 * <p>The sink receives the payload as a {@link JsonNode} (Jackson tree) so
 * downstream code can route on {@code eventType} without needing the
 * concrete Java class.
 */
public final class JdbiOutboxDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(JdbiOutboxDispatcher.class);

    private final Jdbi jdbi;
    private final ObjectMapper json;
    private final BiConsumer<String, JsonNode> sink;

    public JdbiOutboxDispatcher(Jdbi jdbi, BiConsumer<String, JsonNode> sink) {
        this(jdbi, sink, JsonMapper.create());
    }

    JdbiOutboxDispatcher(Jdbi jdbi, BiConsumer<String, JsonNode> sink, ObjectMapper json) {
        this.jdbi = jdbi;
        this.sink = sink;
        this.json = json;
    }

    /**
     * Dispatch up to {@code batchSize} pending events. Returns the number
     * successfully forwarded (rows whose sink completed without throwing).
     */
    public int dispatchPending(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be > 0");
        }
        return jdbi.inTransaction(h -> {
            var rows = h.createQuery("""
                    SELECT id, event_type, payload, attempt_count
                      FROM outbox_events
                     WHERE dispatched = FALSE
                     ORDER BY occurred_at
                     LIMIT :batch
                     FOR UPDATE SKIP LOCKED
                    """)
                .bind("batch", batchSize)
                .map((rs, ctx) -> new PendingRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("event_type"),
                    rs.getString("payload"),
                    rs.getInt("attempt_count")
                ))
                .list();

            int dispatched = 0;
            for (var row : rows) {
                int nextAttempt = row.attemptCount() + 1;
                try {
                    JsonNode node = json.readTree(row.payload());
                    sink.accept(row.eventType(), node);

                    h.createUpdate("""
                            UPDATE outbox_events
                               SET dispatched = TRUE,
                                   dispatched_at = :dispatchedAt,
                                   attempt_count = :attempt
                             WHERE id = :id
                            """)
                        .bind("dispatchedAt", Instant.now())
                        .bind("attempt", nextAttempt)
                        .bind("id", row.id())
                        .execute();
                    dispatched++;
                } catch (JsonProcessingException ex) {
                    LOG.error("Failed to parse outbox payload id={} eventType={}",
                        row.id(), row.eventType(), ex);
                    bumpAttemptOnly(h, row.id(), nextAttempt);
                } catch (RuntimeException ex) {
                    LOG.error("Sink rejected outbox event id={} eventType={} (attempt {})",
                        row.id(), row.eventType(), nextAttempt, ex);
                    bumpAttemptOnly(h, row.id(), nextAttempt);
                }
            }
            return dispatched;
        });
    }

    private void bumpAttemptOnly(org.jdbi.v3.core.Handle h, UUID id, int newAttempt) {
        h.createUpdate("UPDATE outbox_events SET attempt_count = :attempt WHERE id = :id")
            .bind("attempt", newAttempt)
            .bind("id", id)
            .execute();
    }

    private record PendingRow(UUID id, String eventType, String payload, int attemptCount) {}
}
