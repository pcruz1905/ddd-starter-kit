package myfluxo.application.auth;

import myfluxo.application.auth.fakes.FakeCredentialsRepository;
import myfluxo.application.auth.fakes.FakeDomainEventPublisher;
import myfluxo.application.auth.fakes.FakePasswordHasher;
import myfluxo.application.auth.fakes.FakeRefreshTokenRepository;
import myfluxo.application.auth.fakes.FakeRefreshTokenStrategy;
import myfluxo.application.auth.fakes.FakeTokenIssuer;
import myfluxo.application.auth.fakes.FakeUnitOfWork;
import myfluxo.application.auth.fakes.FakeUserRepository;
import myfluxo.application.auth.usecases.ChangePassword;
import myfluxo.application.auth.usecases.Login;
import myfluxo.application.auth.usecases.Logout;
import myfluxo.application.auth.usecases.RefreshSession;
import myfluxo.application.auth.usecases.Register;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Common test fixture wiring fakes into every auth use case at a
 * fixed clock. Each test instantiates one and operates on the
 * in-memory state directly.
 */
final class AuthFixture {

    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    static final Duration REFRESH_TTL = Duration.ofDays(7);
    static final Duration ACCESS_TTL = Duration.ofMinutes(15);

    final FakeUserRepository users = new FakeUserRepository();
    final FakeCredentialsRepository credentials = new FakeCredentialsRepository();
    final FakeRefreshTokenRepository refreshTokens = new FakeRefreshTokenRepository();
    final FakePasswordHasher hasher = new FakePasswordHasher();
    final FakeTokenIssuer tokenIssuer = new FakeTokenIssuer(ACCESS_TTL);
    final FakeRefreshTokenStrategy refreshStrategy = new FakeRefreshTokenStrategy();
    final FakeUnitOfWork uow = new FakeUnitOfWork();
    final FakeDomainEventPublisher events = new FakeDomainEventPublisher();
    final AuthAuditLogger audit = new AuthAuditLogger();
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    final Register register = new Register(
        users, credentials, refreshTokens,
        hasher, tokenIssuer, refreshStrategy, events, audit,
        uow, clock, RefreshTokenTtl.of(REFRESH_TTL)
    );

    final Login login = new Login(
        users, credentials, refreshTokens,
        hasher, tokenIssuer, refreshStrategy, audit,
        uow, clock, RefreshTokenTtl.of(REFRESH_TTL)
    );

    final RefreshSession refreshSession = new RefreshSession(
        refreshTokens, users, refreshStrategy, tokenIssuer, audit,
        uow, clock, RefreshTokenTtl.of(REFRESH_TTL)
    );

    final Logout logout = new Logout(
        refreshTokens, refreshStrategy, audit, uow, clock
    );

    final ChangePassword changePassword = new ChangePassword(
        credentials, refreshTokens, hasher, audit, uow, clock
    );
}
