package myfluxo.kernel.event;

import myfluxo.kernel.aggregate.AbstractAggregateRoot;
import myfluxo.kernel.result.Result;

/**
 * Port for publishing domain events.
 *
 * <p>Use cases drain events from the aggregate
 * ({@link AbstractAggregateRoot#pullEvents}) inside the unit of work,
 * then hand them here. The production implementation
 * ({@code JdbiOutboxDomainEventPublisher}) writes them to the
 * transactional outbox so the events commit atomically with the
 * aggregate; a separate dispatcher forwards them to whatever the
 * actual messaging system is (Kafka, webhooks, in-process subscribers).
 *
 * <p>The application layer doesn't know any of that — it just calls
 * {@code publish} and {@code publishAll}. Cross-cutting subscribers
 * (in-process listeners, audit sinks) plug into the dispatcher, not
 * this publisher.
 */
public interface DomainEventPublisher {

    void publish(DomainEvent event);

    default void publishAll(Iterable<? extends DomainEvent> events) {
        for (var event : events) {
            publish(event);
        }
    }
}
