package myfluxo.kernel.money;

import java.util.Currency;

/**
 * Thrown when an operation requires two {@link Money} values in the same
 * currency but the operands disagree.
 *
 * <p>Extends {@link IllegalArgumentException} so callers that catch the
 * standard Java illegal-argument hierarchy still see it, while the typed
 * subclass lets domain code pattern-match on the specific failure.
 */
public final class CurrencyMismatchException extends IllegalArgumentException {

    private final Currency expected;
    private final Currency actual;

    public CurrencyMismatchException(Currency expected, Currency actual) {
        super("Cannot operate on two Money values with different currencies: "
            + "expected " + expected.getCurrencyCode()
            + " but got " + actual.getCurrencyCode());
        this.expected = expected;
        this.actual = actual;
    }

    public Currency expected() {
        return expected;
    }

    public Currency actual() {
        return actual;
    }
}
