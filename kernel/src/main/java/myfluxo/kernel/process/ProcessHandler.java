package myfluxo.kernel.process;

import myfluxo.kernel.event.DomainEvent;

import java.util.Optional;

/**
 * Concrete behaviour for one type of long-running process.
 *
 * <p>One implementation per business workflow. For example,
 * {@code CheckoutProcessHandler} would coordinate inventory reservation
 * → payment → fulfilment → confirmation email; on failure it would
 * orchestrate compensation (release inventory, refund the payment).
 *
 * <p>The handler is invoked by infrastructure (the
 * {@code ProcessDispatcher}) when a relevant event arrives. It reads
 * the current {@link ProcessInstance} state, examines the incoming
 * event, and decides:
 * <ul>
 *     <li>How to advance — return a new {@link Advancement} carrying
 *         the next state JSON and a status transition.</li>
 *     <li>Whether the event is irrelevant — return {@code Optional.empty()},
 *         the dispatcher leaves the instance untouched.</li>
 * </ul>
 *
 * <p>State serialisation is the handler's responsibility — it knows
 * the shape inside the opaque {@code state} string.
 *
 * @param <S> the concrete state type the handler deserialises the
 *            {@code state} string into. Typically a record.
 */
public interface ProcessHandler<S> {

    /** Matches {@link ProcessInstance#processType()}. */
    String processType();

    /**
     * Handle one event for this instance. Return an {@link Advancement}
     * if the event applies and the state moves forward; return empty
     * if the event is irrelevant.
     */
    Optional<Advancement<S>> onEvent(ProcessInstance instance, DomainEvent event);

    /**
     * Decision returned by {@link #onEvent}: a (possibly terminal)
     * status transition plus the next state.
     */
    record Advancement<S>(
        ProcessInstance.Status status,
        S nextState
    ) {
        public Advancement {
            if (status == null) throw new IllegalArgumentException("status");
            if (nextState == null) throw new IllegalArgumentException("nextState");
        }
    }
}
