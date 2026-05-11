package myfluxo.kernel.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * RFC 9562 UUID version 7 generator — time-ordered, 128-bit, sortable.
 *
 * <p>v7 layout (big-endian):
 * <pre>
 *   bits  0-47:  Unix epoch milliseconds (48 bits)
 *   bits 48-51:  version (always 0b0111 = 7)
 *   bits 52-63:  rand_a (12 random bits)
 *   bits 64-65:  variant (always 0b10)
 *   bits 66-127: rand_b (62 random bits)
 * </pre>
 *
 * <p>Why use v7 over v4:
 * <ul>
 *     <li><b>~50% better insert performance</b> on B-tree primary keys
 *         compared to v4, because new IDs land near each other in the index
 *         instead of scattering randomly.</li>
 *     <li>Sortable by creation time without needing a separate {@code created_at}
 *         column for ordering.</li>
 *     <li>Standardized in RFC 9562 (May 2024); mature ecosystem support.</li>
 * </ul>
 */
public final class UuidV7 {

    private static final SecureRandom RNG = new SecureRandom();

    private UuidV7() {}

    public static UUID generate() {
        return generate(System.currentTimeMillis());
    }

    /** Generate at a specific millisecond — useful for testing. */
    public static UUID generate(long unixMillis) {
        if (unixMillis < 0 || unixMillis > 0xFFFF_FFFF_FFFFL) {
            throw new IllegalArgumentException("unixMillis out of range for UUIDv7: " + unixMillis);
        }
        long randA = RNG.nextLong() & 0x0FFFL;             // 12 bits
        long randB = RNG.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL; // 62 bits

        // msb = ts(48) | ver(4) | rand_a(12)
        long msb = (unixMillis << 16) | (0x7L << 12) | randA;
        // lsb = variant(2 = 0b10) | rand_b(62)
        long lsb = (0x8000_0000_0000_0000L) | randB;

        return new UUID(msb, lsb);
    }
}
