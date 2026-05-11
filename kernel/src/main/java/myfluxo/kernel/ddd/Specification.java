package myfluxo.kernel.ddd;

import java.util.function.Predicate;

/**
 * A reusable, composable yes/no rule over a value. Repositories can accept
 * a Specification to filter results without inventing per-query methods,
 * and use cases can compose specs to express richer policies.
 */
@FunctionalInterface
public interface Specification<T> extends Predicate<T> {

    @Override
    boolean test(T candidate);

    default Specification<T> and(Specification<? super T> other) {
        return c -> this.test(c) && other.test(c);
    }

    default Specification<T> or(Specification<? super T> other) {
        return c -> this.test(c) || other.test(c);
    }

    @Override
    default Specification<T> negate() {
        return c -> !this.test(c);
    }
}
