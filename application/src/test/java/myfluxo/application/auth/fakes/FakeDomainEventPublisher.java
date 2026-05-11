package myfluxo.application.auth.fakes;

import myfluxo.kernel.event.DomainEvent;
import myfluxo.kernel.event.DomainEventPublisher;

import java.util.ArrayList;
import java.util.List;

public final class FakeDomainEventPublisher implements DomainEventPublisher {

    public final List<DomainEvent> published = new ArrayList<>();

    @Override
    public void publish(DomainEvent event) {
        published.add(event);
    }

    @Override
    public void publishAll(Iterable<? extends DomainEvent> events) {
        events.forEach(published::add);
    }
}
