package myfluxo.adapter.persistence.jdbc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record component whose column holds Postgres {@code jsonb}.
 *
 * <p>Binding a Java {@code String} to a {@code jsonb} column needs an
 * explicit cast — the driver won't coerce {@code text} to {@code jsonb}
 * silently. {@link RecordSql} emits {@code CAST(:name AS jsonb)} in
 * place of the bare {@code :name} placeholder for any component marked
 * with this annotation, so INSERT and UPDATE SET round-trip cleanly.
 *
 * <p>The {@code @Target} is {@code METHOD} (not {@code RECORD_COMPONENT})
 * by the same convention as JDBI's {@code @ColumnName}: Java's compiler
 * propagates the annotation to the record's accessor method, which is
 * what {@link RecordSql} reads via {@code RecordComponent#getAccessor}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface JsonbColumn {
}
