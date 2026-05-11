package myfluxo.adapter.persistence.jdbc;

import jakarta.inject.Singleton;
import myfluxo.application.UnitOfWork;
import myfluxo.kernel.result.Result;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Production {@link UnitOfWork} backed by JDBI 3. Implements
 * {@link TransactionalHandle} so the same object serves both roles —
 * repositories ask it for a Handle and it knows whether we're inside a
 * transaction or not.
 *
 * <p>The current transactional Handle is propagated through the call
 * stack using a Java 25 {@link ScopedValue} — no ThreadLocal, no
 * implicit mutable state, and the binding cannot leak out of the
 * transaction scope.
 *
 * <p>Semantics:
 * <ul>
 *     <li>Work returns {@link Result.Ok} → commit, return Ok</li>
 *     <li>Work returns {@link Result.Err} → rollback, return Err</li>
 *     <li>Work throws → rollback, rethrow</li>
 * </ul>
 *
 * <p>Nested {@code inTransaction} calls reuse the outer transaction (the
 * inner call's commit/rollback decision is absorbed by the outer's
 * decision — same semantics as Spring's {@code REQUIRED} propagation).
 */
@Singleton
public final class JdbiUnitOfWork implements UnitOfWork, TransactionalHandle {

    private static final ScopedValue<Handle> CURRENT = ScopedValue.newInstance();

    private final Jdbi jdbi;

    public JdbiUnitOfWork(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public <T> T withHandle(Function<Handle, T> work) {
        if (CURRENT.isBound()) {
            return work.apply(CURRENT.get());
        }
        return jdbi.withHandle(work::apply);
    }

    @Override
    public void useHandle(Consumer<Handle> work) {
        if (CURRENT.isBound()) {
            work.accept(CURRENT.get());
            return;
        }
        jdbi.useHandle(work::accept);
    }

    @Override
    public <T, E> Result<T, E> inTransaction(Supplier<Result<T, E>> work) {
        if (CURRENT.isBound()) {
            return work.get();
        }
        return runOuterTransaction(work);
    }

    private <T, E> Result<T, E> runOuterTransaction(Supplier<Result<T, E>> work) {
        try (Handle h = jdbi.open()) {
            h.begin();
            Result<T, E> result;
            try {
                // CallableOp is parameterized on the work's exception type;
                // our Supplier<Result> only throws RuntimeException, so this
                // call cannot raise checked exceptions. The single
                // RuntimeException catch is sufficient.
                result = ScopedValue.where(CURRENT, h).call(work::get);
            } catch (RuntimeException ex) {
                safeRollback(h);
                throw ex;
            }

            if (result.isOk()) {
                h.commit();
            } else {
                h.rollback();
            }
            return result;
        }
    }

    private static void safeRollback(Handle h) {
        try {
            h.rollback();
        } catch (RuntimeException ignored) {
            // The original exception is more interesting; swallow this one.
        }
    }
}
