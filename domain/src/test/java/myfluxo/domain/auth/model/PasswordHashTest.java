package myfluxo.domain.auth.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordHashTest {

    private static final String SAMPLE =
        "$argon2id$v=19$m=19456,t=2,p=1$c2FsdHNhbHQ$aGFzaGhhc2g";

    @Test
    void of_acceptsNonBlankEncodedForm() {
        var h = PasswordHash.of(SAMPLE);
        assertThat(h.encoded()).isEqualTo(SAMPLE);
    }

    @Test
    void of_rejectsBlank() {
        assertThatThrownBy(() -> PasswordHash.of(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PasswordHash.of("   "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_rejectsNull() {
        assertThatThrownBy(() -> PasswordHash.of(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toString_doesNotLeakEncodedHash() {
        var h = PasswordHash.of(SAMPLE);
        assertThat(h.toString())
            .doesNotContain("argon2id")
            .doesNotContain("c2FsdHNhbHQ")
            .contains("REDACTED");
    }

    @Test
    void equals_byEncodedValue() {
        assertThat(PasswordHash.of(SAMPLE)).isEqualTo(PasswordHash.of(SAMPLE));
        assertThat(PasswordHash.of(SAMPLE))
            .isNotEqualTo(PasswordHash.of(SAMPLE + "X"));
    }
}
