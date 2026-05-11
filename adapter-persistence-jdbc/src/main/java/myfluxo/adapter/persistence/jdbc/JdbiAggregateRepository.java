package myfluxo.adapter.persistence.jdbc;

import myfluxo.kernel.aggregate.AbstractAggregateRoot;
import myfluxo.kernel.aggregate.OptimisticConcurrencyException;
import myfluxo.kernel.id.Identifier;

import java.util.Optional;

/**
 * Reusable JDBI repository base for {@link AbstractAggregateRoot}-style
 * aggregates. Owns the CRUD-shaped methods every aggregate repo needs
 * — {@link #findById}, {@link #save}, {@link #delete}, {@link #restore} —
 * driven by the row record's {@link Table#TABLE} metadata.
 *
 * <p>Subclasses provide three small bridges between aggregate and row:
 * <ul>
 *     <li>{@link #toRow} — aggregate → row, given the version to persist.</li>
 *     <li>{@link #toAggregate} — row → aggregate (rehydration).</li>
 *     <li>{@link #idFromRow} — row → typed ID (needed only to build
 *         the {@link OptimisticConcurrencyException}).</li>
 * </ul>
 * Aggregate-specific finders (e.g. {@code findByEmail}, {@code existsByEmail})
 * stay on the concrete subclass.
 *
 * <h2>Lifecycle owned here</h2>
 * <ol>
 *     <li>{@code save(aggregate)} computes {@code newVersion = aggregate.version() + 1}.</li>
 *     <li>{@code isNew} routes to INSERT, otherwise to a version-checked UPDATE.</li>
 *     <li>The row is built via {@link #toRow}, bound via {@link RecordSql#bindMap},
 *         executed via the {@link Table}-derived SQL constants.</li>
 *     <li>{@link AbstractAggregateRoot#markPersisted} advances in-memory state.</li>
 * </ol>
 *
 * <h2>What this does NOT fit</h2>
 * Record-style aggregates that don't extend {@link AbstractAggregateRoot}
 * (e.g. {@code ProcessInstance}, where {@code version} is pre-bumped and
 * there's no {@code markPersisted}). Those keep their hand-written repos.
 */
public abstract class JdbiAggregateRepository<
    A extends AbstractAggregateRoot<ID>,
    ID extends Identifier<?>,
    R extends Record
> {

    protected final TransactionalHandle tx;
    protected final Table<R> table;

    private final String findByIdSql;
    private final String insertSql;
    private final String updateSql;
    private final String deleteSql;

    /**
     * @param tx the transactional handle source.
     * @param table row-record metadata; carries name, columns, mapper.
     * @param updateExcept additional param names to exclude from the
     *     UPDATE SET clause. {@code "id"} is always excluded; pass e.g.
     *     {@code "createdAt"} for immutable audit columns.
     */
    protected JdbiAggregateRepository(
        TransactionalHandle tx,
        Table<R> table,
        String... updateExcept
    ) {
        this.tx = tx;
        this.table = table;
        this.findByIdSql = table.selectAll()
            + " WHERE " + table.col("id") + " = :id";
        this.insertSql = table.insert();
        this.updateSql = table.updateByIdWithVersion(updateExcept);
        this.deleteSql = table.deleteById();
    }

    /** Aggregate → row, stamped with the version about to be persisted. */
    protected abstract R toRow(A aggregate, long newVersion);

    /** Row → aggregate (rehydration from persistence). */
    protected abstract A toAggregate(R row);

    /** Row → typed ID, used to build the {@link OptimisticConcurrencyException} message. */
    protected abstract ID idFromRow(R row);

    public Optional<A> findById(ID id) {
        return tx.withHandle(h -> h.createQuery(findByIdSql)
            .bind("id", id.value())
            .mapTo(table.rowType())
            .findFirst())
            .map(this::toAggregate);
    }

    public void save(A aggregate) {
        // Single source of truth for the version bump: same value is
        // written to the row, used in the UPDATE's SET clause, and
        // handed to the aggregate. The loaded version is the WHERE
        // guard.
        long newVersion = aggregate.version() + 1L;
        R row = toRow(aggregate, newVersion);
        if (aggregate.isNew()) {
            insert(row);
        } else {
            updateWithVersionCheck(row, aggregate.version());
        }
        aggregate.markPersisted(newVersion);
    }

    public void delete(ID id) {
        tx.useHandle(h -> h.createUpdate(deleteSql)
            .bind("id", id.value())
            .execute());
    }

    /**
     * Unconditional INSERT for an aggregate rehydrated from archive.
     * Bypasses {@link #save}'s INSERT-vs-UPDATE routing because the row
     * has been deleted and {@code save} would route to UPDATE and fail.
     */
    public void restore(A aggregate) {
        long newVersion = aggregate.version() + 1L;
        insert(toRow(aggregate, newVersion));
        aggregate.markPersisted(newVersion);
    }

    protected final void insert(R row) {
        tx.useHandle(h -> h.createUpdate(insertSql)
            .bindMap(RecordSql.bindMap(row))
            .execute());
    }

    protected final void updateWithVersionCheck(R row, long expectedVersion) {
        int rows = tx.withHandle(h -> h.createUpdate(updateSql)
            .bindMap(RecordSql.bindMap(row))
            .bind("expectedVersion", expectedVersion)
            .execute());
        if (rows == 0) {
            throw new OptimisticConcurrencyException(idFromRow(row), expectedVersion);
        }
    }
}
