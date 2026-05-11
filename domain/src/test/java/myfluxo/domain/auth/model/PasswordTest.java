package myfluxo.domain.auth.model;

import myfluxo.kernel.result.Result;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordTest {

    private static <T, E> E unwrapErr(Result<T, E> result) {
        return result.fold(
            ok -> { throw new AssertionError("Expected Err but got Ok: " + ok); },
            err -> err
        );
    }


    @Test
    void parse_acceptsExactlyMinimumLength() {
        var result = Password.parse("a".repeat(Password.MIN_LENGTH));
        assertThat(result.isOk()).isTrue();
    }

    @Test
    void parse_acceptsExactlyMaximumLength() {
        var result = Password.parse("a".repeat(Password.MAX_LENGTH));
        assertThat(result.isOk()).isTrue();
    }

    @Test
    void parse_rejectsBelowMinimum() {
        var result = Password.parse("a".repeat(Password.MIN_LENGTH - 1));
        assertThat(result.isErr()).isTrue();
        assertThat(unwrapErr(result).reason()).isEqualTo("too_short");
    }

    @Test
    void parse_rejectsAboveMaximum() {
        var result = Password.parse("a".repeat(Password.MAX_LENGTH + 1));
        assertThat(result.isErr()).isTrue();
        assertThat(unwrapErr(result).reason()).isEqualTo("too_long");
    }

    @Test
    void parse_rejectsNull() {
        var result = Password.parse(null);
        assertThat(result.isErr()).isTrue();
        assertThat(unwrapErr(result).reason()).isEqualTo("null");
    }

    @Test
    void parse_rejectsNulByte() {
        var result = Password.parse("password\0evil");
        assertThat(result.isErr()).isTrue();
        assertThat(unwrapErr(result).reason()).isEqualTo("contains_null_byte");
    }

    @Test
    void parse_acceptsSpaces_passphrasesAreEncouraged() {
        // Modern OWASP guidance: long passphrases are stronger than
        // short complex passwords. Spaces are allowed.
        var result = Password.parse("correct horse battery staple");
        assertThat(result.isOk()).isTrue();
    }

    @Test
    void parse_acceptsUnicode() {
        var result = Password.parse("pässwörd123_🔒");
        assertThat(result.isOk()).isTrue();
    }

    @Test
    void toString_doesNotLeakPlaintext() {
        var pw = Password.of("supersecret123");
        assertThat(pw.toString())
            .doesNotContain("supersecret123")
            .contains("REDACTED");
    }

    @Test
    void of_throwsOnInvalid() {
        assertThatThrownBy(() -> Password.of("short"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("too_short");
    }

    @Test
    void equals_byValue() {
        assertThat(Password.of("samevalue123"))
            .isEqualTo(Password.of("samevalue123"));
        assertThat(Password.of("samevalue123"))
            .isNotEqualTo(Password.of("differentvalue123"));
    }
}
