package myfluxo.adapter.persistence.jdbc;

import org.jdbi.v3.core.Handle;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Repository-facing port for executing work against the current JDBI
 * {@link Handle}.
 *
 * <p>If the call is inside a {@code uow.inTransaction(...)} block, the
 * Handle bound to that block is reused — every repository operation in
 * the block participates in the same transaction. Otherwise a fresh,
 * auto-committing Handle is opened for the operation and closed when
 * the work returns.
 *
 * <p>Repositories depend on this interface (not on {@code Jdbi} directly)
 * so they don't need to know about Scoped Values, transactions, or how
 * the UoW is implemented.
 */
public interface TransactionalHandle {

    <T> T withHandle(Function<Handle, T> work);

    void useHandle(Consumer<Handle> work);
}
