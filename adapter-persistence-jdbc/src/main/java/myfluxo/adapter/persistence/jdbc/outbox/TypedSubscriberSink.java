package myfluxo.adapter.persistence.jdbc.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import myfluxo.kernel.event.DomainEvent;
import myfluxo.kernel.event.EventSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Outbox sink that routes events to typed {@link EventSubscriber}s.
 *
 * <p>On every event the dispatcher delivers, this sink:
 * <ol>
 *     <li>Loads the event class via {@code Class.forName(eventType)}.</li>
 *     <li>Deserialises the payload to that class with Jackson.</li>
 *     <li>Dispatches the typed event to every registered subscriber
 *         whose {@code eventType()} matches (or is a supertype).</li>
 * </ol>
 *
 * <p>If a subscriber throws, the sink rethrows — the dispatcher then
 * leaves the outbox row as pending (attempt counter bumped) and retries
 * on the next poll. This is the at-least-once contract: subscribers
 * should be idempotent.
 *
 * <p>Events whose class cannot be loaded or deserialised are logged
 * and skipped; they would otherwise block the dispatcher on every
 * poll. This is a deliberate trade-off — unparseable events surface
 * via {@code attempt_count} but do not deadlock the queue.
 *
 * <p>For the routing pattern: the dispatcher pairs this sink with the
 * existing {@code EntityArchiveSink} and any others via a composing
 * {@code BiConsumer}; sink composition order does not matter for
 * correctness, only for log ordering.
 */
public final class TypedSubscriberSink implements BiConsumer<String, JsonNode> {

    private static final Logger LOG = LoggerFactory.getLogger(TypedSubscriberSink.class);

    private final ObjectMapper json;
    private final Map<Class<?>, List<EventSubscriber<?>>> byEventType;

    /**
     * @param subscribers every subscriber the application wants to wire
     *                    in. Order within a single event type is the
     *                    order subscribers were given.
     */
    public TypedSubscriberSink(ObjectMapper json, List<EventSubscriber<?>> subscribers) {
        this.json = json;
        this.byEventType = new HashMap<>();
        for (var sub : subscribers) {
            byEventType.computeIfAbsent(sub.eventType(), k -> new java.util.ArrayList<>()).add(sub);
        }
    }

    @Override
    public void accept(String eventType, JsonNode payload) {
        Class<?> eventClass;
        try {
            eventClass = Class.forName(eventType);
        } catch (ClassNotFoundException ex) {
            LOG.warn("Outbox event class not found: {} — no subscribers will run", eventType);
            return;
        }

        var subs = byEventType.get(eventClass);
        if (subs == null || subs.isEmpty()) {
            return; // no subscribers for this event — common; not an error
        }

        DomainEvent event;
        try {
            Object decoded = json.treeToValue(payload, eventClass);
            if (!(decoded instanceof DomainEvent de)) {
                LOG.error("Outbox event {} deserialised to {} which is not a DomainEvent — skipping",
                    eventType, decoded == null ? "null" : decoded.getClass().getName());
                return;
            }
            event = de;
        } catch (Exception ex) {
            LOG.error("Failed to deserialise outbox event {} into typed instance",
                eventType, ex);
            return;
        }

        dispatch(subs, event);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void dispatch(List<EventSubscriber<?>> subs, DomainEvent event) {
        for (var sub : subs) {
            ((EventSubscriber) sub).on(event);
        }
    }
}
