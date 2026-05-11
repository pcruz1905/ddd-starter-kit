package myfluxo.kernel.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Runs multiple independent computations in parallel on virtual threads
 * and awaits all results.
 *
 * <p>Typical use: fetching several independent aggregates in a use case
 * (e.g., the current user, their recent orders, and product recommendations)
 * — instead of sequential calls, fan out and wait. Each fork runs on its
 * own virtual thread, so even hundreds of in-flight fetches stay cheap.
 *
 * <p>Implementation note: this is the stable virtual-thread Executor API
 * available in JDK 21+. When {@code StructuredTaskScope} (JEP 525+ /
 * targeting JDK 27) finalizes, this can switch to the structured-concurrency
 * variant for cleaner cancellation propagation — the public API here is
 * shaped to make that swap trivial.
 */
public final class ParallelFetch {

    private ParallelFetch() {}

    /** Run two suppliers in parallel; returns both results once both complete. */
    public static <A, B> Pair<A, B> two(Supplier<A> a, Supplier<B> b) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<A> fa = executor.submit(a::get);
            Future<B> fb = executor.submit(b::get);
            return new Pair<>(awaitFuture(fa), awaitFuture(fb));
        }
    }

    /** Run three suppliers in parallel; returns all three results. */
    public static <A, B, C> Triple<A, B, C> three(Supplier<A> a, Supplier<B> b, Supplier<C> c) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<A> fa = executor.submit(a::get);
            Future<B> fb = executor.submit(b::get);
            Future<C> fc = executor.submit(c::get);
            return new Triple<>(awaitFuture(fa), awaitFuture(fb), awaitFuture(fc));
        }
    }

    /** Run N suppliers in parallel; returns results in submission order. */
    public static <T> List<T> all(List<? extends Supplier<? extends T>> suppliers) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>(suppliers.size());
            for (Supplier<? extends T> s : suppliers) {
                futures.add(executor.submit(s::get));
            }
            List<T> results = new ArrayList<>(suppliers.size());
            for (Future<T> f : futures) {
                results.add(awaitFuture(f));
            }
            return results;
        }
    }

    private static <T> T awaitFuture(Future<T> f) {
        try {
            return f.get();
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException re) throw re;
            if (ex.getCause() instanceof Error err) throw err;
            throw new RuntimeException(ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
        }
    }

    public record Pair<A, B>(A first, B second) {}
    public record Triple<A, B, C>(A first, B second, C third) {}
}
