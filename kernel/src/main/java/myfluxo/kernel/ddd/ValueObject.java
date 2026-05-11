package myfluxo.kernel.ddd;

/**
 * Marker for value objects. Value objects are immutable, identity-less, and
 * compared structurally. Prefer Java {@code record} types to satisfy this
 * contract automatically.
 */
public interface ValueObject {
}
