package myfluxo.kernel.aggregate;

import myfluxo.kernel.id.Identifier;

/**
 * Marker for aggregate roots. An aggregate root is the only entity within
 * an aggregate that external code is allowed to reference; it owns all
 * invariants for its aggregate and is the unit of persistence.
 *
 * <p>Identity is exposed via {@link #id()} so repositories and equality
 * checks have a single, typed entry point.
 */
public interface AggregateRoot<ID extends Identifier<?>> {
    ID id();
}
