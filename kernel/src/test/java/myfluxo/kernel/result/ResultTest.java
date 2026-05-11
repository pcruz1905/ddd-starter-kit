package myfluxo.kernel.result;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResultTest {

    @Test
    void ok_carriesValueAndIsOk() {
        Result<Integer, String> r = Result.ok(42);
        assertThat(r.isOk()).isTrue();
        assertThat(r.isErr()).isFalse();
        assertThat(r.orElseThrow()).isEqualTo(42);
    }

    @Test
    void err_carriesErrorAndIsErr() {
        Result<Integer, String> r = Result.err("boom");
        assertThat(r.isErr()).isTrue();
        assertThat(r.isOk()).isFalse();
        assertThatThrownBy(r::orElseThrow);
    }

    @Test
    void map_transformsOkValueOnly() {
        assertThat(Result.<Integer, String>ok(2).map(x -> x * 10).orElse(-1)).isEqualTo(20);
        assertThat(Result.<Integer, String>err("x").map(x -> x * 10).orElse(-1)).isEqualTo(-1);
    }

    @Test
    void flatMap_chainsResults() {
        var doubled = Result.<Integer, String>ok(2)
            .flatMap(x -> Result.<Integer, String>ok(x * 2));
        assertThat(doubled.orElseThrow()).isEqualTo(4);

        var failed = Result.<Integer, String>ok(2)
            .flatMap(x -> Result.<Integer, String>err("nope"));
        assertThat(failed.isErr()).isTrue();
    }

    @Test
    void flatMapError_canRecoverIntoOk() {
        Result<Integer, String> recovered = Result.<Integer, String>err("fail")
            .flatMapError(err -> Result.ok(0));
        assertThat(recovered.orElseThrow()).isEqualTo(0);
    }

    @Test
    void flatMapError_canRetargetError() {
        Result<Integer, RuntimeException> retargeted = Result.<Integer, String>err("nope")
            .flatMapError(msg -> Result.err(new RuntimeException(msg)));
        assertThat(retargeted.isErr()).isTrue();
    }

    @Test
    void tap_runsOnlyOnOk() {
        var captured = new AtomicReference<Integer>();
        Result.<Integer, String>ok(7).tap(captured::set);
        assertThat(captured.get()).isEqualTo(7);

        var counter = new AtomicInteger();
        Result.<Integer, String>err("x").tap(v -> counter.incrementAndGet());
        assertThat(counter.get()).isEqualTo(0);
    }

    @Test
    void tapError_runsOnlyOnErr() {
        var captured = new AtomicReference<String>();
        Result.<Integer, String>err("kaboom").tapError(captured::set);
        assertThat(captured.get()).isEqualTo("kaboom");

        var counter = new AtomicInteger();
        Result.<Integer, String>ok(7).tapError(e -> counter.incrementAndGet());
        assertThat(counter.get()).isEqualTo(0);
    }

    @Test
    void orElseThrowWithMapper_throwsCallerException() {
        assertThatThrownBy(() -> Result.<Integer, String>err("kaboom")
            .orElseThrow(e -> new IllegalStateException("got: " + e)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("kaboom");
    }

    @Test
    void orElseGet_appliesErrorWhenErr() {
        assertThat(Result.<Integer, String>err("fail").orElseGet(e -> e.length()))
            .isEqualTo(4);
    }

    @Test
    void fold_collapsesToSingleType() {
        String okStr = Result.<Integer, String>ok(7).fold(Object::toString, e -> "err:" + e);
        String errStr = Result.<Integer, String>err("nope").fold(Object::toString, e -> "err:" + e);
        assertThat(okStr).isEqualTo("7");
        assertThat(errStr).isEqualTo("err:nope");
    }

    @Test
    void attempt_capturesThrown() {
        Result<Integer, String> r = Result.attempt(
            () -> { throw new IllegalStateException("kaboom"); },
            t -> t.getMessage()
        );
        assertThat(r.isErr()).isTrue();
        assertThat(r.toOptional()).isEmpty();
    }

    @Test
    void okRejectsNullValue() {
        assertThatThrownBy(() -> new Result.Ok<String, String>(null))
            .isInstanceOf(NullPointerException.class);
    }
}
