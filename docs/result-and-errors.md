# Result & domain errors

> Failures are values. Exceptions are for bugs.

Every use case in this kit returns `Result<T, E>` where `E` is a **sealed sum type** of every way that operation can fail. The call site pattern-matches; the compiler checks exhaustiveness. There is no other way for a use case to signal failure.

## The contract

`kernel/src/main/java/myfluxo/kernel/result/Result.java`

```java
public sealed interface Result<T, E> {
    record Ok<T, E>(T value) implements Result<T, E> {}
    record Err<T, E>(E error) implements Result<T, E> {}

    // ... map / flatMap / mapError / fold / orElseThrow / toOptional ...
}
```

Two variants. Records. Both fields are non-null (the records throw on null in their canonical constructor). No magic states like "success-but-also-null".

## Why not exceptions

| Exceptions | `Result<T, E>` |
| --- | --- |
| Invisible in the type signature | Visible in the return type |
| Easy to forget to handle | Compiler-checked exhaustiveness |
| Stack traces carry HTTP-status assumptions | Errors are pure domain values |
| `catch (Exception e)` swallows everything | Each error variant is its own type |
| Cross checked/unchecked distinction is noise | One uniform shape |

Exceptions still exist — they signal **bugs**, not domain outcomes. A misconfigured database connection, a NullPointerException, an OutOfMemoryError: these are exceptions. "User entered the wrong password" is not — it's an expected, named domain outcome.

## A use case in full

`application/src/main/java/myfluxo/application/auth/usecases/Login.java` boils down to:

```java
public Result<AuthSession, AuthError> handle(LoginCommand cmd) {
    return uow.inTransaction(() -> {
        var user = users.findByEmail(cmd.email());
        if (user.isEmpty()) {
            hasher.verifyDecoy(cmd.password());  // timing defense
            audit.loginFailure(cmd.email().value(), "user_not_found");
            return Result.<AuthSession, AuthError>err(new AuthError.InvalidCredentials());
        }
        // ... etc ...
    });
}
```

The HTTP route matches:

```java
switch (login.handle(cmd)) {
    case Ok<AuthSession, AuthError>(AuthSession s) -> respond200(s);
    case Err<AuthSession, AuthError>(AuthError err) -> switch (err) {
        case AuthError.InvalidCredentials __     -> respond401("invalid_credentials");
        case AuthError.AccountInactive a         -> respond403("account_inactive");
        case AuthError.RefreshTokenReuseDetected __ -> respond401("refresh_token_reuse_detected");
        // every variant covered — adding a new one breaks this compile
        default -> respond500();  // for variants not reachable from Login
    };
}
```

Adding `AuthError.PasswordExpired` to the sealed hierarchy makes every `switch` over `AuthError` fail to compile until it's handled. **You cannot accidentally ignore a new failure mode.**

## Sealed domain errors

`domain/src/main/java/myfluxo/domain/auth/errors/AuthError.java`:

```java
public sealed interface AuthError extends DomainError {
    record InvalidCredentials() implements AuthError {}
    record InvalidRefreshToken() implements AuthError {}
    record RefreshTokenReuseDetected() implements AuthError {}
    record Forbidden(Permission required) implements AuthError {}
    record Unauthenticated() implements AuthError {}
    record AccountInactive(UserId userId) implements AuthError {}
    record WeakPassword(String reason) implements AuthError {}
    record OldPasswordMismatch() implements AuthError {}
    record EmailAlreadyTaken(Email email) implements AuthError {}
    record InvalidEmail(String input, String reason) implements AuthError {}
}
```

Each variant **carries the data the caller needs to react** — `Forbidden` knows the required permission, `EmailAlreadyTaken` knows the email. No stringly-typed error codes that the caller has to parse.

## UnitOfWork and Result

`JdbiUnitOfWork.inTransaction` uses the Result variant to decide commit vs rollback:

| Inner closure returns | UoW does |
| --- | --- |
| `Result.Ok(...)`  | `COMMIT`, returns Ok |
| `Result.Err(...)` | `ROLLBACK`, returns Err |
| throws | `ROLLBACK`, rethrows |

This is the only "side-effecting" semantics. Once a use case completes, the transaction outcome matches the Result variant — no `@Transactional` annotations, no rollback-on-exception heuristics, no "did this throw count as a rollback?" guessing.

⚠️ **Gotcha**: if you need a side-effect to commit even when the use case returns Err (e.g., revoking a refresh-token family on theft detection), it must run in its own transaction *before* the failing one — otherwise the rollback eats your revoke. See `RefreshSession.java` for the two-transaction pattern.

## Result combinators

`Result` ships with the usual functional toolkit:

```java
result
  .map(user -> toDto(user))                    // change Ok type
  .mapError(e -> toHttpError(e))               // change Err type
  .flatMap(user -> nextStep(user))             // chain another Result
  .flatMapError(e -> recover(e))               // recover from Err
  .tap(user -> audit.log(user))                // side-effect on Ok
  .tapError(e -> audit.log(e))                 // side-effect on Err
  .fold(this::onOk, this::onErr);              // reduce to a single value
```

Prefer pattern-matching at boundaries (HTTP routes) and combinators inside use-case bodies.

## See also

- [`docs/auth.md`](auth.md) — `AuthError` mapped to HTTP responses
- [`docs/persistence.md`](persistence.md) — `UnitOfWork.inTransaction` and Result interplay
