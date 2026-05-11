package myfluxo.domain.auth;

import myfluxo.domain.users.model.UserId;

import java.util.Optional;

/**
 * Port for persisting and loading {@link Credentials} aggregates.
 * Implementation in {@code adapter-persistence-jdbc}.
 */
public interface CredentialsRepository {

    /** Lookup credentials by user id. Empty if the user has none. */
    Optional<Credentials> findByUserId(UserId userId);

    /** Persist new or updated credentials. Enforces optimistic concurrency. */
    void save(Credentials credentials);

    /** Hard-delete credentials. Used in the User-deletion flow. */
    void delete(UserId userId);
}
