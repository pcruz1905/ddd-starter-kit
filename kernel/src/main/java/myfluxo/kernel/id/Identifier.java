package myfluxo.kernel.id;

/**
 * Marker for value-object identifiers (typed IDs). The wrapped value is
 * accessed via {@link #value()}.
 *
 * <p>Concrete IDs are records, with new ids minted via the kernel's
 * {@code UuidV7} generator (time-ordered, B-tree-friendly):
 * <pre>{@code
 * public record UserId(UUID value) implements Identifier<UUID> {
 *     public UserId {
 *         if (value == null) throw new IllegalArgumentException("id required");
 *     }
 *     public static UserId newId() { return new UserId(UuidV7.generate()); }
 * }
 * }</pre>
 */
public interface Identifier<V> {
    V value();
}
