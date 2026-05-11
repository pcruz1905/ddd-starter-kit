package myfluxo.kernel.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParallelFetchTest {

    @Test
    void two_returnsBothResults() {
        var pair = ParallelFetch.two(
            () -> "first",
            () -> 42
        );
        assertThat(pair.first()).isEqualTo("first");
        assertThat(pair.second()).isEqualTo(42);
    }

    @Test
    void three_returnsAllThree() {
        var triple = ParallelFetch.three(
            () -> 1,
            () -> "two",
            () -> 3.0
        );
        assertThat(triple.first()).isEqualTo(1);
        assertThat(triple.second()).isEqualTo("two");
        assertThat(triple.third()).isEqualTo(3.0);
    }

    @Test
    void all_returnsResultsInSubmissionOrder() {
        List<Supplier<? extends Integer>> suppliers = List.of(
            () -> 1, () -> 2, () -> 3, () -> 4
        );
        var results = ParallelFetch.all(suppliers);
        assertThat(results).containsExactly(1, 2, 3, 4);
    }

    @Test
    void all_runsConcurrently() throws Exception {
        long start = System.nanoTime();
        ParallelFetch.three(
            () -> sleepThen(100, "a"),
            () -> sleepThen(100, "b"),
            () -> sleepThen(100, "c")
        );
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        // If they truly ran in parallel, total time is ~100ms, not ~300ms.
        // Allow generous headroom for CI variability.
        assertThat(elapsedMs)
            .as("three 100ms suppliers in parallel should complete well under 300ms")
            .isLessThan(250L);
    }

    @Test
    void exceptionInOneFork_propagatesToCaller() {
        assertThatThrownBy(() -> ParallelFetch.two(
            () -> "ok",
            () -> { throw new IllegalStateException("kaboom"); }
        )).isInstanceOf(IllegalStateException.class)
          .hasMessage("kaboom");
    }

    private static String sleepThen(long millis, String value) {
        try {
            Thread.sleep(millis);
            return value;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
        }
    }
}
