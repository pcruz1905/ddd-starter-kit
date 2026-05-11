package myfluxo.domain.users.errors;

import myfluxo.domain.shared.model.Email;
import myfluxo.domain.users.model.UserId;
import myfluxo.kernel.result.DomainError;

/**
 * Every way a User-related operation can fail, as a sealed sum type. Code
 * that handles UserError must pattern-match every variant; the compiler
 * catches added variants at every call site.
 */
public sealed interface UserError extends DomainError {

    record EmailAlreadyTaken(Email email) implements UserError {}

    record UserNotFound(UserId id) implements UserError {}

    record InvalidEmail(String input, String reason) implements UserError {}

    record AlreadyActive(UserId id) implements UserError {}

    record AlreadyDeactivated(UserId id) implements UserError {}

    record CannotActivateDeactivated(UserId id) implements UserError {}
}
