package myfluxo.adapter.http;

/**
 * Wire-level shape for an HTTP error response.
 *
 * <ul>
 *     <li>{@code code} — stable, machine-readable identifier (e.g.,
 *         {@code "EMAIL_ALREADY_TAKEN"}). Clients can switch on this.</li>
 *     <li>{@code message} — human-readable description, intentionally
 *         <strong>sanitized</strong>: it never contains stack traces,
 *         database error codes, internal class names, file paths, or
 *         anything else that hints at server internals.</li>
 *     <li>{@code correlationId} — opaque token, also logged on the server.
 *         When a user reports a 500, support copies this id and grep the
 *         logs. {@code null} for errors that don't need server-side
 *         lookup (e.g., a typed domain error).</li>
 * </ul>
 */
public record ErrorResponse(String code, String message, String correlationId) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null);
    }

    public static ErrorResponse withCorrelation(String code, String message, String correlationId) {
        return new ErrorResponse(code, message, correlationId);
    }
}
