package myfluxo.domain.shared.model;

import myfluxo.kernel.result.Result;
import myfluxo.kernel.ddd.ValueObject;

import java.util.regex.Pattern;

/**
 * Email value object. Validated at construction — once you hold an Email,
 * you know it's well-formed.
 *
 * <p>Construction returns a {@link Result} because parsing user input can
 * fail and we never want to throw for domain validation. Use {@link #parse}
 * at boundaries; use the canonical constructor only when you trust the
 * source (e.g., loading from a row you already validated on write).
 */
public record Email(String value) implements ValueObject {

    private static final Pattern BASIC_EMAIL =
        Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    public Email {
        if (value == null || !BASIC_EMAIL.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid email: " + value);
        }
    }

    /**
     * Parse user input into an Email, returning a typed error instead of
     * throwing. Prefer this at application boundaries.
     */
    public static Result<Email, ParseError> parse(String input) {
        if (input == null || input.isBlank()) {
            return Result.err(new ParseError(input, "empty"));
        }
        if (!BASIC_EMAIL.matcher(input).matches()) {
            return Result.err(new ParseError(input, "not_well_formed"));
        }
        return Result.ok(new Email(input));
    }

    public record ParseError(String input, String reason) {}
}
