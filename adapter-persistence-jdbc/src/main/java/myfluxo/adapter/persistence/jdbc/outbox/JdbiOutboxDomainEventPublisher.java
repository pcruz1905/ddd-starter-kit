package myfluxo.adapter.persistence.jdbc.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;
import myfluxo.adapter.persistence.jdbc.JsonMapper;
import myfluxo.adapter.persistence.jdbc.TransactionalHandle;
import myfluxo.kernel.event.DomainEvent;
import myfluxo.kernel.event.DomainEventPublisher;
import myfluxo.kernel.id.UuidV7;


/**
 * Transactional outbox publisher.
 *
 * <p>Writes events to the {@code outbox_events} table using the current
 * UoW's {@link TransactionalHandle} — so the row is committed atomically
 * with the aggregate that produced it. There is no window where the
 * write commits but the event is missing, and no window where the event
 * exists but the write was rolled back.
 *
 * <p>A separate {@link JdbiOutboxDispatcher} polls the table and
 * forwards pending rows to wherever they actually need to go (Kafka,
 * webhooks, an in-memory bus, etc.).
 */
@Singleton
public final class JdbiOutboxDomainEventPublisher implements DomainEventPublisher {

    private final TransactionalHandle tx;
    private final ObjectMapper json;

    public JdbiOutboxDomainEventPublisher(TransactionalHandle tx) {
        this(tx, JsonMapper.create());
    }

    JdbiOutboxDomainEventPublisher(TransactionalHandle tx, ObjectMapper json) {
        this.tx = tx;
        this.json = json;
    }

    @Override
    public void publish(DomainEvent event) {
        var payload = serialize(event);
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
            .bind("payload", payload)
            .bind("occurredAt", event.occurredAt())
            .execute());
    }

    private String serialize(DomainEvent event) {
        try {
            return json.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                "Failed to serialize event of type " + event.getClass().getName(), ex);
        }
    }
}
