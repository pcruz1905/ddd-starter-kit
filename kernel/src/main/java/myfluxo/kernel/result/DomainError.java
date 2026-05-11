package myfluxo.kernel.result;

/**
 * Root of every bounded context's error hierarchy. Errors are values, not
 * exceptions — they flow through {@link Result#err}.
 *
 * <p>Each bounded context defines its own sealed sub-hierarchy:
 *
 * <pre>{@code
 * public sealed interface UserError extends DomainError {
 *     record EmailAlreadyTaken(Email email) implements UserError {}
 *     record UserNotFound(UserId id) implements UserError {}
 *     record InvalidEmail(String input, String reason) implements UserError {}
 * }
 * }</pre>
 *
 * <p>Pattern-match exhaustively on the sealed type to handle every case.
 */
public interface DomainError {
}
