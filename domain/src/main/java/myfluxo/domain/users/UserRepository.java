package myfluxo.domain.users;

import myfluxo.domain.shared.model.Email;
import myfluxo.domain.users.model.UserId;

import java.util.Optional;

/**
 * Repository port for the User aggregate. Lives in the domain layer; the
 * persistence adapter modules implement it.
 *
 * <p>Methods return {@link Optional} for honest absence and {@code void}
 * for state-changing operations that always succeed at this layer
 * (transactional / consistency concerns are the use case's responsibility,
 * not the repository's).
 */
public interface UserRepository {

    Optional<User> findById(UserId id);

    Optional<User> findByEmail(Email email);

    boolean existsByEmail(Email email);

    /**
     * Persist a new or modified User. The repository is responsible only
     * for storing what it's given — it does not enforce uniqueness, that's
     * a domain rule the use case checks before saving.
     */
    void save(User user);

    void delete(UserId id);

    /**
     * Re-insert a previously archived User. Unlike {@link #save}, this
     * does not consult {@code isNew()} — the caller asserts the primary
     * row has been deleted (typically via the archive flow) and wants
     * to bring it back. The persisted version advances by one from the
     * archived version to keep the audit history continuous.
     *
     * <p>Throws if the row already exists; that would indicate the
     * caller's "row was deleted" assumption is wrong.
     */
    void restore(User user);
}
