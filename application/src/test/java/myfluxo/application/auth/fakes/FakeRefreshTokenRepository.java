package myfluxo.application.auth.fakes;

import myfluxo.domain.auth.RefreshToken;
import myfluxo.domain.auth.RefreshTokenRepository;
import myfluxo.domain.auth.model.RefreshTokenFamilyId;
import myfluxo.domain.auth.model.RefreshTokenId;
import myfluxo.domain.auth.model.TokenHash;
import myfluxo.domain.users.model.UserId;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class FakeRefreshTokenRepository implements RefreshTokenRepository {

    private final Map<RefreshTokenId, RefreshToken> byId = new HashMap<>();
    private final Map<String, RefreshTokenId> byHash = new HashMap<>();

    @Override
    public Optional<RefreshToken> findByTokenHash(TokenHash tokenHash) {
        return Optional.ofNullable(byHash.get(tokenHash.encoded()))
            .map(byId::get);
    }

    @Override
    public Optional<RefreshToken> findById(RefreshTokenId id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public void save(RefreshToken token) {
        byId.put(token.id(), token);
        byHash.put(token.tokenHash().encoded(), token.id());
        token.markPersisted(token.version() + 1L);
    }

    @Override
    public void revokeFamily(RefreshTokenFamilyId familyId, Instant now) {
        for (RefreshToken t : byId.values()) {
            if (t.familyId().equals(familyId)) {
                t.revoke(now);
            }
        }
    }

    @Override
    public void revokeAllForUser(UserId userId, Instant now) {
        for (RefreshToken t : byId.values()) {
            if (t.userId().equals(userId)) {
                t.revoke(now);
            }
        }
    }

    public int size() {
        return byId.size();
    }
}
