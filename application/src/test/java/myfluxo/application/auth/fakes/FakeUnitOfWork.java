package myfluxo.application.auth.fakes;

import myfluxo.application.UnitOfWork;
import myfluxo.kernel.result.Result;

import java.util.function.Supplier;

/**
 * Pass-through UoW for unit tests — runs the work supplier and returns
 * its result. No real transaction semantics; throws-rollback isn't
 * meaningful in-memory.
 *
 * <p>What this fake does NOT test: that the real UoW correctly commits
 * on Ok and rolls back on Err / exception. That's covered by
 * {@code JdbiUnitOfWorkIT} in {@code adapter-persistence-jdbc}.
 */
public final class FakeUnitOfWork implements UnitOfWork {

    @Override
    public <T, E> Result<T, E> inTransaction(Supplier<Result<T, E>> work) {
        return work.get();
    }
}
