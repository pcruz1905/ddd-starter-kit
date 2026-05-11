package myfluxo.application.auth.fakes;

import myfluxo.domain.shared.model.Email;
import myfluxo.domain.users.User;
import myfluxo.domain.users.UserRepository;
import myfluxo.domain.users.model.UserId;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class FakeUserRepository implements UserRepository {

    private final Map<UserId, User> byId = new HashMap<>();
    private final Map<String, UserId> byEmail = new HashMap<>();  // case-insensitive index

    @Override
    public Optional<User> findById(UserId id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<User> findByEmail(Email email) {
        return Optional.ofNullable(byEmail.get(normalize(email)))
            .map(byId::get);
    }

    @Override
    public boolean existsByEmail(Email email) {
        return byEmail.containsKey(normalize(email));
    }

    @Override
    public void save(User user) {
        byId.put(user.id(), user);
        byEmail.put(normalize(user.email()), user.id());
        user.markPersisted(user.version() + 1L);
    }

    @Override
    public void delete(UserId id) {
        var existing = byId.remove(id);
        if (existing != null) {
            byEmail.remove(normalize(existing.email()));
        }
    }

    @Override
    public void restore(User user) {
        save(user);
    }

    private static String normalize(Email email) {
        return email.value().toLowerCase(Locale.ROOT);
    }
}
