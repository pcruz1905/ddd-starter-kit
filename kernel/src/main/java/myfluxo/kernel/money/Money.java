package myfluxo.kernel.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable monetary amount: a fixed-point integer count of minor units
 * plus an ISO 4217 currency. The same convention every serious financial
 * system uses (Stripe, Square, banks).
 *
 * <p>Minor units are currency-specific and follow {@link Currency#getDefaultFractionDigits}:
 * <ul>
 *     <li>USD, EUR, GBP (2 digits): 1 minor unit = 1 cent / pence; $1.23 → {@code 123}</li>
 *     <li>JPY, KRW, VND (0 digits): 1 minor unit = 1 whole unit; ¥1500 → {@code 1500}</li>
 *     <li>BHD, KWD, JOD (3 digits): 1 minor unit = 1 fils; BD 1.500 → {@code 1500}</li>
 * </ul>
 *
 * <p>The minor-unit count is stored as {@code long}, so the maximum
 * representable amount is ~9.2 × 10^18 minor units — far larger than any
 * realistic transaction. Arbitrary precision (e.g., for tax-rate
 * multiplication) is available through {@link #times(BigDecimal)} and
 * {@link #dividedBy(long)}, which internally promote to {@link BigDecimal}
 * and round back to the currency's scale.
 *
 * <p><b>Currency safety:</b> arithmetic between different currencies
 * throws {@link CurrencyMismatchException}. There is no implicit
 * conversion — exchange rates are not part of this type.
 *
 * <p>For e-commerce aggregates (Order, Product, Refund, LineItem) all
 * money fields are {@code Money}. For DB columns, store
 * {@code amount_minor_units BIGINT NOT NULL} and {@code currency_code TEXT NOT NULL}
 * as two columns, then assemble {@code Money.of(amount, Currency.getInstance(code))}
 * in the row mapper.
 */
public final class Money {

    private static final RoundingMode DEFAULT_ROUNDING = RoundingMode.HALF_EVEN;

    private final long minorUnits;
    private final Currency currency;

    private Money(long minorUnits, Currency currency) {
        this.minorUnits = minorUnits;
        this.currency = Objects.requireNonNull(currency, "currency");
    }

    // ── Factories ───────────────────────────────────────────────────────

    public static Money of(long minorUnits, Currency currency) {
        return new Money(minorUnits, currency);
    }

    public static Money of(long minorUnits, String currencyCode) {
        return new Money(minorUnits, Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(0L, currency);
    }

    /**
     * Build from a decimal amount in base units (e.g., $1.23 → 123 cents),
     * rounded to the currency's scale with banker's rounding.
     */
    public static Money fromBigDecimal(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        int scale = currency.getDefaultFractionDigits();
        long minor = amount.movePointRight(scale).setScale(0, DEFAULT_ROUNDING).longValueExact();
        return new Money(minor, currency);
    }

    /** Parse a string of base units, e.g. {@code Money.parse("9.99", USD)}. */
    public static Money parse(String amount, Currency currency) {
        return fromBigDecimal(new BigDecimal(amount), currency);
    }

    // ── Accessors ───────────────────────────────────────────────────────

    public long minorUnits() {
        return minorUnits;
    }

    public Currency currency() {
        return currency;
    }

    // ── Arithmetic (currency-safe) ──────────────────────────────────────

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public Money times(long factor) {
        return new Money(Math.multiplyExact(minorUnits, factor), currency);
    }

    /**
     * Multiply by an arbitrary decimal factor (e.g., a tax rate of 0.07).
     * Result is rounded to the currency's scale using banker's rounding.
     */
    public Money times(BigDecimal factor) {
        Objects.requireNonNull(factor, "factor");
        BigDecimal result = BigDecimal.valueOf(minorUnits).multiply(factor);
        long rounded = result.setScale(0, DEFAULT_ROUNDING).longValueExact();
        return new Money(rounded, currency);
    }

    /**
     * Divide by an integer divisor (e.g., split a total across N items).
     * Result is rounded to the nearest minor unit using banker's rounding.
     * Throws {@link ArithmeticException} on zero divisor.
     */
    public Money dividedBy(long divisor) {
        if (divisor == 0L) {
            throw new ArithmeticException("Cannot divide Money by zero");
        }
        BigDecimal result = BigDecimal.valueOf(minorUnits)
            .divide(BigDecimal.valueOf(divisor), 0, DEFAULT_ROUNDING);
        return new Money(result.longValueExact(), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    public Money abs() {
        return minorUnits >= 0L ? this : negate();
    }

    public Money max(Money other) {
        requireSameCurrency(other);
        return minorUnits >= other.minorUnits ? this : other;
    }

    public Money min(Money other) {
        requireSameCurrency(other);
        return minorUnits <= other.minorUnits ? this : other;
    }

    // ── Predicates / Comparison ─────────────────────────────────────────

    public boolean isZero() {
        return minorUnits == 0L;
    }

    public boolean isPositive() {
        return minorUnits > 0L;
    }

    public boolean isNegative() {
        return minorUnits < 0L;
    }

    public boolean gt(Money other) {
        requireSameCurrency(other);
        return minorUnits > other.minorUnits;
    }

    public boolean lt(Money other) {
        requireSameCurrency(other);
        return minorUnits < other.minorUnits;
    }

    public boolean gte(Money other) {
        requireSameCurrency(other);
        return minorUnits >= other.minorUnits;
    }

    public boolean lte(Money other) {
        requireSameCurrency(other);
        return minorUnits <= other.minorUnits;
    }

    // ── Conversion ──────────────────────────────────────────────────────

    /** Decimal value in base units (e.g., 9.99 for 999 USD minor units). */
    public BigDecimal toBigDecimal() {
        int scale = currency.getDefaultFractionDigits();
        return BigDecimal.valueOf(minorUnits).movePointLeft(scale);
    }

    /**
     * Plain numeric string in base units, zero-padded to the currency's
     * scale. No currency symbol, no locale formatting. Examples:
     * <ul>
     *     <li>USD 123 → {@code "1.23"}</li>
     *     <li>JPY 1500 → {@code "1500"}</li>
     *     <li>BHD 1500 → {@code "1.500"}</li>
     * </ul>
     */
    public String toPlainString() {
        return toBigDecimal().toPlainString();
    }

    /**
     * Locale-formatted display string with currency symbol, e.g.
     * {@code "$1.23"} or {@code "¥1,500"}. Use for UI. For storage,
     * persist {@link #minorUnits} + {@link #currency} as two columns.
     */
    public String toDisplayString(Locale locale) {
        var formatter = java.text.NumberFormat.getCurrencyInstance(locale);
        formatter.setCurrency(currency);
        return formatter.format(toBigDecimal());
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + toPlainString();
    }

    // ── Equality ────────────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        return o instanceof Money m
            && m.minorUnits == minorUnits
            && m.currency.equals(currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(minorUnits, currency);
    }

    // ── Internal ────────────────────────────────────────────────────────

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }
}
