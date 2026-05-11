# Adding a new aggregate

> The recipe. First time: a few hours. Second time: ~30 minutes. After that you're skimming.

This doc walks through adding a hypothetical `Product` aggregate end-to-end. The same eight steps work for any aggregate.

---

## The eight steps

1. **Flyway migration** — create the table.
2. **Row record** — `ProductRow` + `public static final Table<ProductRow> TABLE`.
3. **Domain aggregate** — `Product` extends `AbstractAggregateRoot<ProductId>`.
4. **Repository port** — `ProductRepository` interface in `domain.products`.
5. **JDBI repository** — `JdbiProductRepository` extends `JdbiAggregateRepository<...>`.
6. **Register the row mapper** — add to `JdbiSetup`.
7. **Register in the drift gate** — add to `SchemaDriftIT.REGISTERED_TABLES`.
8. **Use case + HTTP route** — `CreateProduct` + `POST /v1/products`.

---

## 1. Flyway migration

`adapter-persistence-jdbc/src/main/resources/db/migration/V<n>__create_products.sql`:

```sql
CREATE TABLE products (
    id           UUID PRIMARY KEY,
    sku          TEXT NOT NULL UNIQUE,
    name         TEXT NOT NULL,
    price_cents  BIGINT NOT NULL CHECK (price_cents >= 0),
    version      BIGINT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_products_sku ON products (sku);
```

Naming convention: `V<sequence>__<snake_case_description>.sql`. The migration runs once per database; Flyway records its checksum.

---

## 2. Row record

`adapter-persistence-jdbc/src/main/java/myfluxo/adapter/persistence/jdbc/products/ProductRow.java`:

```java
public record ProductRow(
    UUID id,
    String sku,
    String name,
    long priceCents,
    long version,
    Instant createdAt,
    Instant updatedAt
) {
    public static final Table<ProductRow> TABLE = Table.of("products", ProductRow.class);
}
```

Camel-case Java fields map to snake_case columns automatically. `@ColumnName("legacy_name")` overrides if needed.

---

## 3. Domain aggregate

`domain/src/main/java/myfluxo/domain/products/Product.java`:

```java
public final class Product extends AbstractAggregateRoot<ProductId> {

    private final ProductId id;
    private final Sku sku;
    private String name;
    private FiatMoney price;
    private final Instant createdAt;
    private Instant updatedAt;

    private Product(...) { super(); /* new */ }
    private Product(..., long version) { super(version); /* rehydrate */ }

    public static Product create(Sku sku, String name, FiatMoney price, Instant now) {
        var p = new Product(ProductId.newId(), sku, name, price, now);
        p.recordEvent(new ProductCreated(p.id, sku, now));
        return p;
    }

    public static Product rehydrate(...) { ... }

    public void rename(String newName, Instant now) {
        // enforce invariants here
        this.name = newName;
        this.updatedAt = now;
        recordEvent(new ProductRenamed(id, newName, now));
    }
}
```

Rules:
- All state changes go through methods that enforce invariants.
- State changes record domain events via `recordEvent(...)`.
- Two constructors: one for `create` (version 0), one for `rehydrate` (carries version).
- Value objects (`Sku`, `FiatMoney`, `ProductId`) live in `domain.products.model`.

---

## 4. Repository port

`domain/src/main/java/myfluxo/domain/products/ProductRepository.java`:

```java
public interface ProductRepository {
    Optional<Product> findById(ProductId id);
    Optional<Product> findBySku(Sku sku);
    void save(Product product);
}
```

Interface lives in `domain`. The implementation comes later, in `adapter-persistence-jdbc`. Use cases depend on the port.

---

## 5. JDBI repository

`adapter-persistence-jdbc/src/main/java/myfluxo/adapter/persistence/jdbc/products/JdbiProductRepository.java`:

```java
@Singleton
public final class JdbiProductRepository implements ProductRepository {

    private final TransactionalHandle handle;

    public JdbiProductRepository(TransactionalHandle handle) { this.handle = handle; }

    @Override
    public Optional<Product> findById(ProductId id) {
        return handle.withHandle(h -> h.createQuery(
                ProductRow.TABLE.selectAll() + " WHERE id = :id")
            .bind("id", id.value())
            .map(ProductRow.TABLE.rowMapperFactory())
            .findOne()
            .map(ProductMapper::toDomain));
    }

    @Override
    public void save(Product product) {
        var row = ProductMapper.toRow(product);
        if (product.isNew()) {
            handle.useHandle(h -> h.createUpdate(ProductRow.TABLE.insert())
                .bindBean(row).execute());
        } else {
            int rows = handle.withHandle(h -> h.createUpdate(
                    ProductRow.TABLE.updateByIdWithVersion("createdAt"))
                .bindBean(row)
                .bind("expectedVersion", product.version())
                .execute());
            if (rows == 0) {
                throw new OptimisticConcurrencyException(product.id());
            }
        }
    }
}
```

`ProductMapper` is plain static methods between `Product ↔ ProductRow`. Lives in the adapter (never imported by domain).

---

## 6. Register the row mapper

`adapter-persistence-jdbc/.../JdbiSetup.java`:

```java
jdbi.registerRowMapper(UserRow.TABLE.rowMapperFactory());
jdbi.registerRowMapper(CredentialsRow.TABLE.rowMapperFactory());
jdbi.registerRowMapper(RefreshTokenRow.TABLE.rowMapperFactory());
jdbi.registerRowMapper(ProductRow.TABLE.rowMapperFactory());  // ←── new
```

Without this, JDBI can't turn a result row into your record.

---

## 7. Register in the drift gate

`adapter-persistence-jdbc/src/test/java/myfluxo/adapter/persistence/jdbc/SchemaDriftIT.java`:

```java
private static final List<Table<?>> REGISTERED_TABLES = List.of(
    UserRow.TABLE,
    ProcessInstanceRow.TABLE,
    CredentialsRow.TABLE,
    RefreshTokenRow.TABLE,
    ProductRow.TABLE  // ←── new
);
```

The drift check now covers `products`. If you ever rename `price_cents` in a migration but forget to update `ProductRow`, CI fails. See [`docs/schema-drift.md`](schema-drift.md).

---

## 8. Use case + HTTP route

`application/src/main/java/myfluxo/application/products/usecases/CreateProduct.java`:

```java
@Singleton
public final class CreateProduct implements UseCase<CreateProductCommand, ProductDto, ProductError> {

    private final ProductRepository products;
    private final DomainEventPublisher events;
    private final UnitOfWork uow;
    private final Clock clock;

    public CreateProduct(...) { ... }

    @Override
    public Result<ProductDto, ProductError> handle(CreateProductCommand cmd) {
        return uow.inTransaction(() -> {
            if (products.findBySku(cmd.sku()).isPresent()) {
                return Result.err(new ProductError.SkuAlreadyTaken(cmd.sku()));
            }
            var product = Product.create(cmd.sku(), cmd.name(), cmd.price(), clock.instant());
            products.save(product);
            product.events().forEach(events::publish);
            return Result.ok(ProductDto.from(product));
        });
    }
}
```

`adapter-http/src/main/java/myfluxo/adapter/http/products/ProductRoutes.java`:

```java
routes.post("/v1/products", (req, res) -> {
    var auth = bearerAuth.requirePermission(req, Permission.PRODUCTS_WRITE);
    // ... unpack auth ...
    var cmd = parseCommand(req);
    switch (createProduct.handle(cmd)) {
        case Result.Ok<ProductDto, ProductError>(ProductDto dto)  -> respond201(res, dto);
        case Result.Err<ProductDto, ProductError>(ProductError e) -> mapToHttp(res, e);
    }
});
```

---

## Tests

The kit's testing convention:

| Layer | Test type | Location | Tools |
| --- | --- | --- | --- |
| Domain aggregate | Unit | `domain/src/test/...` | JUnit + AssertJ |
| Use case | Unit (fakes) | `application/src/test/...` | JUnit + fake repositories |
| Repository | IT (real Postgres) | `adapter-persistence-jdbc/src/test/...` | Testcontainers |
| HTTP route | IT (real HTTP) | `adapter-persistence-jdbc/src/test/...` | Testcontainers + HttpClient |

Use cases are tested with fakes (in-memory `Map`-backed repos). Repositories are tested against a real Postgres via Testcontainers. Don't mock JDBI.

---

## Permissions

If your new aggregate needs RBAC (most do):

1. Add permissions to `domain.auth.model.Permission`:
   ```java
   public static final Permission PRODUCTS_READ   = new Permission("products", "read");
   public static final Permission PRODUCTS_WRITE  = new Permission("products", "write");
   public static final Permission PRODUCTS_DELETE = new Permission("products", "delete");
   ```
2. Add them to `Permission.ALL`.
3. Grant them to whichever `Role` variants should hold them.
4. Enforce at the route with `bearerAuth.requirePermission(req, Permission.PRODUCTS_WRITE)`.

See [`docs/rbac.md`](rbac.md) for the full pattern.

---

## Checklist

```
[ ] Migration written and tested locally
[ ] Row record created with TABLE constant
[ ] Domain aggregate with create + rehydrate + state-mutating methods
[ ] Repository port in domain
[ ] JDBI repository in adapter-persistence-jdbc
[ ] Row mapper registered in JdbiSetup
[ ] TABLE added to SchemaDriftIT.REGISTERED_TABLES
[ ] Use case + HTTP route
[ ] Permissions added to Permission catalog
[ ] Unit tests for aggregate invariants
[ ] Unit tests for use case (fakes)
[ ] IT for repository (real Postgres)
[ ] IT for HTTP route (E2E)
[ ] `mvn verify` clean
```

---

## See also

- [`docs/persistence.md`](persistence.md) — `Table<R>` and the JDBI patterns the recipe uses
- [`docs/schema-drift.md`](schema-drift.md) — why step 7 matters
- [`docs/rbac.md`](rbac.md) — adding permissions
- [`docs/result-and-errors.md`](result-and-errors.md) — defining `ProductError`
