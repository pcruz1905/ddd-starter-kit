package myfluxo.kernel.money;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency JPY = Currency.getInstance("JPY");
    private static final Currency BHD = Currency.getInstance("BHD");

    @Nested
    class Construction {

        @Test
        void of_minorUnitsAndCurrency() {
            var m = Money.of(1234, USD);
            assertThat(m.minorUnits()).isEqualTo(1234L);
            assertThat(m.currency()).isEqualTo(USD);
        }

        @Test
        void of_minorUnitsAndCurrencyCodeString() {
            var m = Money.of(1234, "EUR");
            assertThat(m.currency()).isEqualTo(EUR);
        }

        @Test
        void zero_factory() {
            assertThat(Money.zero(USD).isZero()).isTrue();
            assertThat(Money.zero(USD).minorUnits()).isZero();
        }

        @Test
        void fromBigDecimal_USD_roundsToCents() {
            assertThat(Money.fromBigDecimal(new BigDecimal("1.23"), USD).minorUnits())
                .isEqualTo(123L);
            assertThat(Money.fromBigDecimal(new BigDecimal("1.234"), USD).minorUnits())
                .as("HALF_EVEN: 0.4 rounds down to 0")
                .isEqualTo(123L);
            assertThat(Money.fromBigDecimal(new BigDecimal("1.235"), USD).minorUnits())
                .as("HALF_EVEN: 0.5 rounds to even (124 is even, would round up; check banker's behavior)")
                .isEqualTo(124L);
        }

        @Test
        void fromBigDecimal_JPY_noFractionalPart() {
            assertThat(Money.fromBigDecimal(new BigDecimal("1500"), JPY).minorUnits())
                .isEqualTo(1500L);
            assertThat(Money.fromBigDecimal(new BigDecimal("1500.49"), JPY).minorUnits())
                .as("JPY has 0 decimal places — rounds to integer")
                .isEqualTo(1500L);
        }

        @Test
        void fromBigDecimal_BHD_threeDecimals() {
            assertThat(Money.fromBigDecimal(new BigDecimal("1.500"), BHD).minorUnits())
                .isEqualTo(1500L);
        }

        @Test
        void parse_string() {
            assertThat(Money.parse("9.99", USD).minorUnits()).isEqualTo(999L);
        }
    }

    @Nested
    class Arithmetic {

        @Test
        void plus_sameCurrency() {
            assertThat(Money.of(100, USD).plus(Money.of(50, USD)).minorUnits()).isEqualTo(150L);
        }

        @Test
        void plus_differentCurrency_throws() {
            assertThatExceptionOfType(CurrencyMismatchException.class)
                .isThrownBy(() -> Money.of(100, USD).plus(Money.of(50, EUR)))
                .satisfies(ex -> {
                    assertThat(ex.expected()).isEqualTo(USD);
                    assertThat(ex.actual()).isEqualTo(EUR);
                });
        }

        @Test
        void minus_negatives_allowed() {
            assertThat(Money.of(100, USD).minus(Money.of(150, USD)).minorUnits())
                .isEqualTo(-50L);
        }

        @Test
        void times_longFactor() {
            assertThat(Money.of(100, USD).times(3).minorUnits()).isEqualTo(300L);
        }

        @Test
        void times_decimalFactor_appliesTaxRate() {
            // $10.00 at 7% tax → $0.70 (= 70 cents)
            var tax = Money.of(1000, USD).times(new BigDecimal("0.07"));
            assertThat(tax.minorUnits()).isEqualTo(70L);
        }

        @Test
        void times_overflow_throws() {
            assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, USD).times(2))
                .isInstanceOf(ArithmeticException.class);
        }

        @Test
        void dividedBy_splitsAcrossUnits() {
            // $10.00 / 3 = $3.33 (banker's rounding)
            assertThat(Money.of(1000, USD).dividedBy(3).minorUnits()).isEqualTo(333L);
        }

        @Test
        void dividedBy_zero_throws() {
            assertThatThrownBy(() -> Money.of(100, USD).dividedBy(0))
                .isInstanceOf(ArithmeticException.class);
        }

        @Test
        void negate_flipsSign() {
            assertThat(Money.of(100, USD).negate().minorUnits()).isEqualTo(-100L);
            assertThat(Money.of(-100, USD).negate().minorUnits()).isEqualTo(100L);
        }

        @Test
        void abs_makesPositive() {
            assertThat(Money.of(-100, USD).abs().minorUnits()).isEqualTo(100L);
            assertThat(Money.of(100, USD).abs().minorUnits()).isEqualTo(100L);
        }

        @Test
        void max_min_returnsCorrectOperand() {
            var a = Money.of(100, USD);
            var b = Money.of(50, USD);
            assertThat(a.max(b)).isEqualTo(a);
            assertThat(a.min(b)).isEqualTo(b);
        }

        @Test
        void max_differentCurrency_throws() {
            assertThatExceptionOfType(CurrencyMismatchException.class)
                .isThrownBy(() -> Money.of(100, USD).max(Money.of(50, EUR)));
        }
    }

    @Nested
    class Predicates {

        @Test
        void isZero_isPositive_isNegative() {
            assertThat(Money.zero(USD).isZero()).isTrue();
            assertThat(Money.of(1, USD).isPositive()).isTrue();
            assertThat(Money.of(-1, USD).isNegative()).isTrue();
        }

        @Test
        void comparisonOps_sameCurrency() {
            var a = Money.of(100, USD);
            var b = Money.of(200, USD);
            assertThat(a.lt(b)).isTrue();
            assertThat(b.gt(a)).isTrue();
            assertThat(a.lte(Money.of(100, USD))).isTrue();
            assertThat(a.gte(Money.of(100, USD))).isTrue();
        }

        @Test
        void comparisonOps_differentCurrency_throws() {
            assertThatExceptionOfType(CurrencyMismatchException.class)
                .isThrownBy(() -> Money.of(100, USD).lt(Money.of(50, EUR)));
        }
    }

    @Nested
    class Conversion {

        @Test
        void toBigDecimal_USD() {
            assertThat(Money.of(1234, USD).toBigDecimal()).isEqualByComparingTo("12.34");
        }

        @Test
        void toBigDecimal_JPY() {
            assertThat(Money.of(1500, JPY).toBigDecimal()).isEqualByComparingTo("1500");
        }

        @Test
        void toBigDecimal_BHD() {
            assertThat(Money.of(1500, BHD).toBigDecimal()).isEqualByComparingTo("1.500");
        }

        @Test
        void toPlainString_USD() {
            assertThat(Money.of(1234, USD).toPlainString()).isEqualTo("12.34");
        }

        @Test
        void toPlainString_JPY_noDecimals() {
            assertThat(Money.of(1500, JPY).toPlainString()).isEqualTo("1500");
        }

        @Test
        void toString_isCurrencyCodePlusPlainString() {
            assertThat(Money.of(1234, USD)).hasToString("USD 12.34");
        }

        @Test
        void toDisplayString_localeFormatted_usEnglish() {
            assertThat(Money.of(123456, USD).toDisplayString(Locale.US))
                .isEqualTo("$1,234.56");
        }
    }

    @Nested
    class Equality {

        @Test
        void equals_sameAmountAndCurrency() {
            assertThat(Money.of(100, USD)).isEqualTo(Money.of(100, USD));
        }

        @Test
        void notEquals_sameAmountDifferentCurrency() {
            assertThat(Money.of(100, USD)).isNotEqualTo(Money.of(100, EUR));
        }

        @Test
        void notEquals_differentAmount() {
            assertThat(Money.of(100, USD)).isNotEqualTo(Money.of(101, USD));
        }

        @Test
        void hashCode_consistentWithEquals() {
            assertThat(Money.of(100, USD)).hasSameHashCodeAs(Money.of(100, USD));
        }
    }
}
