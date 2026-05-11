package myfluxo.application.auth.commands;

import myfluxo.domain.users.model.UserId;

/**
 * Input for the {@code ChangePassword} use case. The caller must be
 * authenticated — the HTTP filter extracts the {@code currentUserId}
 * from the access token's {@code sub} claim and passes it through.
 *
 * <p>Side effect on success: all OTHER refresh tokens for this user
 * are revoked. The token used to make the request stays valid.
 */
public record ChangePasswordCommand(
    UserId currentUserId,
    String oldPassword,
    String newPassword
) {}
