package myfluxo.application;

import myfluxo.kernel.result.Result;

import java.util.function.Supplier;

/**
 * Functional Unit of Work. The block of work is given a chance to either
 * succeed (returning {@code Result.Ok}) or fail (returning {@code Result.Err});
 * the UoW implementation translates that into commit or rollback.
 *
 * <p>Compared to the manual {@code beginTransaction / try / commit / catch /
 * rollback} pattern, this:
 * <ul>
 *     <li>cannot leak (commit/rollback is the adapter's responsibility, not the caller's),</li>
 *     <li>cannot accidentally commit on a domain error (only {@code Ok} commits),</li>
 *     <li>composes naturally with Result-returning use cases.</li>
 * </ul>
 *
 * <p>If the {@code work} throws an unchecked exception, the implementation
 * MUST roll back and rethrow.
 */
public interface UnitOfWork {

    <T, E> Result<T, E> inTransaction(Supplier<Result<T, E>> work);
}
