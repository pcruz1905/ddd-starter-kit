package myfluxo.kernel.ddd;

/**
 * Marker for <em>domain services</em> — operations that don't naturally
 * belong to any single aggregate or value object.
 *
 * <p>Examples: a {@code FundsTransfer} service that mutates two
 * {@code Account} aggregates atomically; a {@code PasswordPolicy} that
 * evaluates a candidate password against rules. Anything that's pure
 * domain logic but doesn't fit on one aggregate.
 *
 * <p>Domain services live in the {@code domain} layer alongside aggregates.
 * They are not the same as application services (use cases), which live in
 * the {@code application} layer and coordinate repositories + UoW.
 *
 * <p>The marker is intentionally empty — it exists to make the role
 * grep-able and to surface the distinction in code review.
 */
public interface DomainService {
}
