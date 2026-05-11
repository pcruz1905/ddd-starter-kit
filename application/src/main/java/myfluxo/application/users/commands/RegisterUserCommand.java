package myfluxo.application.users.commands;

/**
 * Input for the {@code RegisterUser} use case. Application-layer command
 * — not HTTP-shaped; the HTTP adapter has its own
 * {@code RegisterUserRequest} that maps to this.
 */
public record RegisterUserCommand(String email) {

    public static RegisterUserCommand of(String email) {
        return new RegisterUserCommand(email);
    }
}
