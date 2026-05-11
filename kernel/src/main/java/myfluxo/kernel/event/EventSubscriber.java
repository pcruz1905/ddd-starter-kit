package myfluxo.kernel.event;

/**
 * Typed handler for one domain event class. The companion of
 * {@link DomainEventPublisher}: publishers emit events, subscribers
 * react to them.
 *
 * <p>The infrastructure (an outbox sink) deserialises an event from
 * its JSON form, then dispatches it to every registered subscriber
 * whose {@link #eventType} matches. Unlike the raw
 * {@code BiConsumer<String, JsonNode>} sink, this is **typed end to
 * end** — the handler receives a fully-formed event object.
 *
 * <p>Example:
 * <pre>{@code
 * @Singleton
 * public final class WelcomeEmailSubscriber
 *         implements EventSubscriber<UserEvent.Registered> {
 *
 *     @Override public Class<UserEvent.Registered> eventType() {
 *         return UserEvent.Registered.class;
 *     }
 *
 *     @Override public void on(UserEvent.Registered event) {
 *         emailer.sendWelcome(event.email());
 *     }
 * }
 * }</pre>
 *
 * <p>Subscribers are stateless; one instance handles every event.
 * The dispatcher is responsible for delivery (and for retry on
 * failure — typically via the outbox attempt counter, not in the
 * subscriber).
 */
public interface EventSubscriber<E extends DomainEvent> {

    Class<E> eventType();

    void on(E event);
}
