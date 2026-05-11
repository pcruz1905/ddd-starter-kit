package myfluxo.kernel.result;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Sum type for operations that can fail. Either {@link Ok}{@code <T>} or
 * {@link Err}{@code <E>}. Errors are domain values, not thrown.
 *
 * <p>Pattern-match exhaustively:
 * <pre>{@code
 * Result<User, UserError> r = registerUser.handle(cmd);
 * return switch (r) {
 *     case Ok<User, UserError> ok  -> respondCreated(ok.value());
 *     case Err<User, UserError> err -> switch (err.error()) {
 *         case UserError.EmailAlreadyTaken e -> respondConflict(e.email());
 *         case UserError.InvalidEmail e      -> respondBadRequest(e.reason());
 *         // exhaustiveness checked by the compiler
 *     };
 * };
 * }</pre>
 */
public sealed interface Result<T, E> {

    record Ok<T, E>(T value) implements Result<T, E> {
        public Ok {
            if (value == null) {
                throw new NullPointerException("Result.Ok value cannot be null; use Result<Optional<T>, E> or a typed absence error");
            }
        }
    }

    record Err<T, E>(E error) implements Result<T, E> {
        public Err {
            if (error == null) {
                throw new NullPointerException("Result.Err error cannot be null");
            }
        }
    }

    // ── Constructors ────────────────────────────────────────────────────

    static <T, E> Result<T, E> ok(T value) {
        return new Ok<>(value);
    }

    static <T, E> Result<T, E> err(E error) {
        return new Err<>(error);
    }

    /** Lift a possibly-throwing computation into a Result, mapping any thrown exception. */
    static <T, E> Result<T, E> attempt(Supplier<T> supplier, Function<Throwable, E> onThrow) {
        try {
            return ok(supplier.get());
        } catch (Throwable t) {
            return err(onThrow.apply(t));
        }
    }

    // ── Predicates ──────────────────────────────────────────────────────

    default boolean isOk() {
        return this instanceof Ok<T, E>;
    }

    default boolean isErr() {
        return this instanceof Err<T, E>;
    }

    // ── Transformations ─────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    default <U> Result<U, E> map(Function<? super T, ? extends U> f) {
        return switch (this) {
            case Ok<T, E>(T value) -> ok(f.apply(value));
            case Err<T, E> err -> (Result<U, E>) err;
        };
    }

    @SuppressWarnings("unchecked")
    default <U> Result<U, E> flatMap(Function<? super T, ? extends Result<U, E>> f) {
        return switch (this) {
            case Ok<T, E>(T value) -> (Result<U, E>) f.apply(value);
            case Err<T, E> err -> (Result<U, E>) err;
        };
    }

    @SuppressWarnings("unchecked")
    default <F> Result<T, F> mapError(Function<? super E, ? extends F> f) {
        return switch (this) {
            case Ok<T, E> ok -> (Result<T, F>) ok;
            case Err<T, E>(E error) -> err(f.apply(error));
        };
    }

    /** Recover from an error by flat-mapping it into a (possibly successful) Result. */
    @SuppressWarnings("unchecked")
    default <F> Result<T, F> flatMapError(Function<? super E, ? extends Result<T, F>> f) {
        return switch (this) {
            case Ok<T, E> ok -> (Result<T, F>) ok;
            case Err<T, E>(E error) -> (Result<T, F>) f.apply(error);
        };
    }

    // ── Side effects (no value change) ──────────────────────────────────

    /** Run the consumer on the success value, if any. Returns {@code this} for chaining. */
    default Result<T, E> tap(Consumer<? super T> consumer) {
        if (this instanceof Ok<T, E>(T value)) {
            consumer.accept(value);
        }
        return this;
    }

    /** Run the consumer on the error, if any. Returns {@code this} for chaining. */
    default Result<T, E> tapError(Consumer<? super E> consumer) {
        if (this instanceof Err<T, E>(E error)) {
            consumer.accept(error);
        }
        return this;
    }

    // ── Extraction ──────────────────────────────────────────────────────

    /** Returns the success value or throws if this is an error. Use sparingly — prefer pattern matching. */
    default T orElseThrow() {
        return switch (this) {
            case Ok<T, E>(T value) -> value;
            case Err<T, E>(E error) -> throw new NoSuchElementException("Result was Err: " + error);
        };
    }

    /** Returns the success value or throws a caller-supplied exception built from the error. */
    default <X extends RuntimeException> T orElseThrow(Function<? super E, X> exceptionMapper) {
        return switch (this) {
            case Ok<T, E>(T value) -> value;
            case Err<T, E>(E error) -> throw exceptionMapper.apply(error);
        };
    }

    /** Returns the success value or the supplied fallback. */
    default T orElse(T fallback) {
        return switch (this) {
            case Ok<T, E>(T value) -> value;
            case Err<T, E> ignored -> fallback;
        };
    }

    /** Returns the success value or the result of the supplier. */
    default T orElseGet(Function<? super E, ? extends T> fallback) {
        return switch (this) {
            case Ok<T, E>(T value) -> value;
            case Err<T, E>(E error) -> fallback.apply(error);
        };
    }

    /** Returns {@link Optional#empty()} on Err. */
    default Optional<T> toOptional() {
        return switch (this) {
            case Ok<T, E>(T value) -> Optional.of(value);
            case Err<T, E> ignored -> Optional.empty();
        };
    }

    /** Apply one of two reducers depending on the variant. */
    default <R> R fold(Function<? super T, ? extends R> onOk, Function<? super E, ? extends R> onErr) {
        return switch (this) {
            case Ok<T, E>(T value) -> onOk.apply(value);
            case Err<T, E>(E error) -> onErr.apply(error);
        };
    }
}
