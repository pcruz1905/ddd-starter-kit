package myfluxo.application.auth.fakes;

import myfluxo.domain.auth.RefreshTokenStrategy;
import myfluxo.domain.auth.model.TokenHash;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic refresh-token strategy for unit tests.
 *
 * <p>{@link #generatePlaintext} returns sequential strings so tests
 * can predict and assert on specific token values. {@link #hash} is
 * a one-to-one bijective wrapper — different inputs always produce
 * different outputs, same inputs always produce the same output.
 */
public final class FakeRefreshTokenStrategy implements RefreshTokenStrategy {

    private final AtomicLong sequence = new AtomicLong();

    @Override
    public String generatePlaintext() {
        return "fake-token-" + sequence.incrementAndGet();
    }

    @Override
    public TokenHash hash(String plaintext) {
        return TokenHash.of("fakehash::" + plaintext);
    }
}
