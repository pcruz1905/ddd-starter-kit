package myfluxo.application.auth.fakes;

import myfluxo.domain.auth.PasswordHasher;
import myfluxo.domain.auth.model.Password;
import myfluxo.domain.auth.model.PasswordHash;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Trivial hasher for unit tests. Deterministic and fast.
 *
 * <p>NOT secure — uses string concatenation. The real Argon2id impl is
 * in {@code adapter-auth.Argon2PasswordHasher} with its own tests.
 *
 * <p>Tracks {@link #verifyCount} so tests can assert that
 * timing-attack mitigations (always call verify, even on missing user)
 * are intact at the use-case level.
 */
public final class FakePasswordHasher implements PasswordHasher {

    private static final String PREFIX = "fakehash::";

    /** How many times {@link #verify} has been called this fixture's lifetime. */
    public final AtomicInteger verifyCount = new AtomicInteger();

    @Override
    public PasswordHash hash(Password password) {
        return PasswordHash.of(PREFIX + password.value());
    }

    @Override
    public boolean verify(Password password, PasswordHash hash) {
        verifyCount.incrementAndGet();
        return hash.encoded().equals(PREFIX + password.value());
    }

    @Override
    public boolean needsRehash(PasswordHash hash) {
        return false;
    }
}
