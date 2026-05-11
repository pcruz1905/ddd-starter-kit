package myfluxo.kernel.id;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UuidV7Test {

    @Test
    void generate_setsVersionTo7() {
        var uuid = UuidV7.generate();
        assertThat(uuid.version()).isEqualTo(7);
    }

    @Test
    void generate_setsVariantTo2() {
        var uuid = UuidV7.generate();
        // RFC 4122 variant = 0b10 → variant() returns 2.
        assertThat(uuid.variant()).isEqualTo(2);
    }

    @Test
    void generate_idsAreSortedByCreationTime() throws InterruptedException {
        List<java.util.UUID> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ids.add(UuidV7.generate());
            Thread.sleep(2);
        }
        var sortedCopy = new ArrayList<>(ids);
        sortedCopy.sort(java.util.UUID::compareTo);
        assertThat(ids)
            .as("UUID v7 must be sortable by creation time")
            .isEqualTo(sortedCopy);
    }

    @Test
    void generate_idsAreUniqueAcrossManyCalls() {
        var seen = new java.util.HashSet<java.util.UUID>();
        for (int i = 0; i < 10_000; i++) {
            assertThat(seen.add(UuidV7.generate())).isTrue();
        }
    }

    @Test
    void generate_rejectsNegativeTimestamp() {
        assertThatThrownBy(() -> UuidV7.generate(-1L))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
