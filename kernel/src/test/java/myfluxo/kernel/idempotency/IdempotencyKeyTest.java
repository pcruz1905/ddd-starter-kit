package myfluxo.kernel.idempotency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyTest {

    @Test
    void acceptsTypicalKey() {
        var key = new IdempotencyKey("client-attempt-1");
        assertThat(key.value()).isEqualTo("client-attempt-1");
    }

    @Test
    void rejectsNullOrBlank() {
        assertThatThrownBy(() -> new IdempotencyKey(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyKey(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyKey("   "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsExcessivelyLongKey() {
        var tooLong = "x".repeat(201);
        assertThatThrownBy(() -> new IdempotencyKey(tooLong))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
