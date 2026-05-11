package myfluxo.adapter.persistence.jdbc.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import myfluxo.adapter.persistence.jdbc.JsonMapper;
import myfluxo.domain.shared.model.Email;
import myfluxo.domain.users.events.UserEvent;
import myfluxo.domain.users.model.UserId;
import myfluxo.kernel.event.DomainEvent;
import myfluxo.kernel.event.EventSubscriber;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the typed event subscriber sink. Pure dispatch
 * logic — no Postgres, no outbox, just the in-process routing.
 */
class TypedSubscriberSinkTest {

    private static final ObjectMapper JSON = JsonMapper.create();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void dispatchesToSubscriberMatchingExactEventClass() {
        List<UserEvent.Registered> received = new ArrayList<>();
        var sub = new EventSubscriber<UserEvent.Registered>() {
            @Override public Class<UserEvent.Registered> eventType() { return UserEvent.Registered.class; }
            @Override public void on(UserEvent.Registered event) { received.add(event); }
        };
        var sink = new TypedSubscriberSink(JSON, List.of(sub));

        var event = new UserEvent.Registered(
            new UserId(UUID.randomUUID()),
            new Email("alice@example.com"),
            NOW);
        sink.accept(event.getClass().getName(), JSON.valueToTree(event));

        assertThat(received).hasSize(1);
        assertThat(received.get(0).email().value()).isEqualTo("alice@example.com");
    }

    @Test
    void skipsEventClassesWithNoSubscriber() {
        var counter = new AtomicInteger();
        var sub = new EventSubscriber<UserEvent.Activated>() {
            @Override public Class<UserEvent.Activated> eventType() { return UserEvent.Activated.class; }
            @Override public void on(UserEvent.Activated event) { counter.incrementAndGet(); }
        };
        var sink = new TypedSubscriberSink(JSON, List.of(sub));

        // Wrong event class — no subscriber registered for it.
        var registered = new UserEvent.Registered(
            new UserId(UUID.randomUUID()),
            new Email("bob@example.com"),
            NOW);
        sink.accept(registered.getClass().getName(), JSON.valueToTree(registered));

        assertThat(counter.get()).isZero();
    }

    @Test
    void skipsClassNamesThatDoNotResolve() {
        var sub = new EventSubscriber<UserEvent.Registered>() {
            @Override public Class<UserEvent.Registered> eventType() { return UserEvent.Registered.class; }
            @Override public void on(UserEvent.Registered event) { /* unused */ }
        };
        var sink = new TypedSubscriberSink(JSON, List.of(sub));

        // Bad class name — should be a graceful skip, not a throw,
        // so a typo'd event doesn't deadlock the outbox.
        sink.accept("does.not.exist.NotARealClass$Variant",
            JSON.createObjectNode());

        // No exception thrown reaches here.
        assertThat(true).isTrue();
    }

    @Test
    void multipleSubscribersForSameEventType_allRun() {
        var hits = new AtomicInteger();
        EventSubscriber<UserEvent.Registered> first = new EventSubscriber<>() {
            @Override public Class<UserEvent.Registered> eventType() { return UserEvent.Registered.class; }
            @Override public void on(UserEvent.Registered event) { hits.incrementAndGet(); }
        };
        EventSubscriber<UserEvent.Registered> second = new EventSubscriber<>() {
            @Override public Class<UserEvent.Registered> eventType() { return UserEvent.Registered.class; }
            @Override public void on(UserEvent.Registered event) { hits.incrementAndGet(); }
        };
        var sink = new TypedSubscriberSink(JSON, List.of(first, second));

        var event = new UserEvent.Registered(
            new UserId(UUID.randomUUID()),
            new Email("carol@example.com"),
            NOW);
        sink.accept(event.getClass().getName(), JSON.valueToTree(event));

        assertThat(hits.get())
            .as("both subscribers should fire for one event")
            .isEqualTo(2);
    }

    @Test
    void subscriberException_propagatesSoOutboxDispatcherCanRetry() {
        var bomb = new EventSubscriber<UserEvent.Registered>() {
            @Override public Class<UserEvent.Registered> eventType() { return UserEvent.Registered.class; }
            @Override public void on(UserEvent.Registered event) {
                throw new RuntimeException("downstream down");
            }
        };
        var sink = new TypedSubscriberSink(JSON, List.of(bomb));

        var event = new UserEvent.Registered(
            new UserId(UUID.randomUUID()),
            new Email("dave@example.com"),
            NOW);

        assertThatThrownBy(() ->
            sink.accept(event.getClass().getName(), JSON.valueToTree(event)))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("downstream down");
    }

    @Test
    void nonDomainEventClasses_areSkippedGracefully() {
        var sub = new EventSubscriber<UserEvent.Registered>() {
            @Override public Class<UserEvent.Registered> eventType() { return UserEvent.Registered.class; }
            @Override public void on(UserEvent.Registered event) { /* unused */ }
        };
        var sink = new TypedSubscriberSink(JSON, List.of(sub));

        // A class that exists but doesn't implement DomainEvent — sink
        // logs and skips rather than crashing the dispatcher.
        sink.accept(String.class.getName(), JSON.createObjectNode());

        // No exception — graceful skip is the correctness criterion.
        assertThat(true).isTrue();
    }

    @SuppressWarnings("unused")
    private record SomeOtherEvent(Instant occurredAt) implements DomainEvent {}
}
