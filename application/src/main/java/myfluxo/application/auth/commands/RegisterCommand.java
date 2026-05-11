package myfluxo.application.auth.commands;

/**
 * Input for the {@code Register} use case. Plain DTO carrying untrusted
 * inputs; the use case performs all validation.
 *
 * <p>Strings are deliberate — they're the boundary type. Parsing into
 * {@code Email} / {@code Password} value objects happens inside the
 * use case so the failures become typed {@code AuthError} variants.
 */
public record RegisterCommand(String email, String password) {}
