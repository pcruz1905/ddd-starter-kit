package myfluxo.application.auth.fakes;

import myfluxo.domain.auth.Credentials;
import myfluxo.domain.auth.CredentialsRepository;
import myfluxo.domain.users.model.UserId;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class FakeCredentialsRepository implements CredentialsRepository {

    private final Map<UserId, Credentials> byUserId = new HashMap<>();

    @Override
    public Optional<Credentials> findByUserId(UserId userId) {
        return Optional.ofNullable(byUserId.get(userId));
    }

    @Override
    public void save(Credentials credentials) {
        byUserId.put(credentials.userId(), credentials);
        credentials.markPersisted(credentials.version() + 1L);
    }

    @Override
    public void delete(UserId userId) {
        byUserId.remove(userId);
    }
}
