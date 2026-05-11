# Audit log

> Every auth event flows through a dedicated SLF4J channel that production can route independently from application logs.

`application/src/main/java/myfluxo/application/auth/AuthAuditLogger.java`

---

## The channel

```java
private static final Logger LOG = LoggerFactory.getLogger("myfluxo.audit.auth");
```

The logger name `myfluxo.audit.auth` is deliberate. SLF4J routes by logger name, so a logback (or log4j2, or anything else) config can ship this channel anywhere it wants — without touching any code:

```xml
<!-- logback-spring.xml example -->
<appender name="AUDIT_FILE" class="ch.qos.logback.core.FileAppender">
    <file>/var/log/myfluxo/audit-auth.jsonl</file>
    <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
</appender>

<logger name="myfluxo.audit.auth" level="INFO" additivity="false">
    <appender-ref ref="AUDIT_FILE"/>
    <!-- pipe to SIEM, S3, Splunk, etc. -->
</logger>
```

Why a separate channel:

| Same as app logs | Dedicated channel |
| --- | --- |
| Audit events drown in DEBUG noise | Clean stream of security-relevant events |
| Different retention policies are awkward | Configure retention per channel |
| Routing to a SIEM means filtering by message content | Routing is by logger name — clean |

---

## What gets recorded

| Event | Level | Why |
| --- | --- | --- |
| `REGISTERED` | INFO | New account exists; useful for funnel analysis + abuse correlation |
| `LOGIN_SUCCESS` | INFO | Establishes "user X had a valid session at time T" |
| `LOGIN_FAILURE` | **WARN** | Suspicious; SIEM rule: 10 in 5 min from one IP = alert |
| `REFRESHED` | INFO | Establishes session continuity |
| `REFRESH_REUSE_DETECTED` | **WARN** | High-signal: someone is replaying a rotated token. SIEM rule: any of these = page on-call. |
| `LOGGED_OUT` | INFO | Closes the session in the log |
| `PASSWORD_CHANGED` | INFO | Account-recovery / takeover correlation |

WARN is reserved for events that **should trigger a security-team rule**. INFO is for the timeline.

---

## What does NOT get recorded

The audit log is sensitive — it may be widely shipped, replicated, indexed, and read by people who shouldn't see secrets. So by construction, the logger never sees:

- Plaintext passwords
- Password hashes
- Refresh tokens (plaintext or hashed)
- JWTs
- Any other credential material

The audit log carries identifiers and event names, not secrets. The wire format:

```
LOGIN_SUCCESS userId=01956c9b-3a4f-7c00-8a01-... email=alice@example.com
REFRESH_REUSE_DETECTED userId=01956c9b-... familyId=01956ca0-...
LOGIN_FAILURE email=alice@example.com reason=wrong_password
```

`reason=wrong_password` is fine — it's already part of the public response. `reason=user_not_found` is **also fine in the audit log**, even though the public response says `invalid_credentials`. The audit channel is privileged and intended for internal use; that's the whole point of having two surfaces.

---

## Calling sites

| Use case | Audit calls |
| --- | --- |
| `Register` | `audit.registered(userId, email)` on success |
| `Login` | `audit.loginSuccess(...)` or `audit.loginFailure(email, reason)` |
| `RefreshSession` | `audit.refreshed(userId)` on success<br>`audit.refreshReuseDetected(userId, familyId)` on theft detection |
| `Logout` | `audit.loggedOut(userId)` |
| `ChangePassword` | `audit.passwordChanged(userId)` on success |

The use case is responsible for calling the audit logger. Audit lives in the application layer (`application.auth.AuthAuditLogger`), not in the route — because some events (theft detection) happen inside a use case and shouldn't depend on the HTTP layer.

---

## Why a class, not a field on each use case

`AuthAuditLogger` is `@Singleton`-scoped and injected into each use case. Alternatives we don't use:

| Alternative | Problem |
| --- | --- |
| Inline `Logger.info(...)` calls | Fan-out: changing the channel name is 30 edits |
| `@AuditLogged` annotation + AOP | Reflection / proxy magic; not in this kit |
| Event-driven audit (publish a `LoggedIn` event, audit subscriber consumes) | Indirection without benefit for the kit; the outbox is for *business* events, not security telemetry |

A plain class is the simplest thing that lets tests substitute (subclass + capture) and lets prod swap (override the `@Singleton`).

---

## Testing

`AuthAuditLogger` is subclassable. Use-case unit tests can inject a capturing subclass:

```java
class CapturingAuditLogger extends AuthAuditLogger {
    final List<String> events = new ArrayList<>();
    @Override public void loginSuccess(UserId u, Email e) { events.add("LOGIN_SUCCESS:" + u); }
    // ... etc ...
}
```

…and assert on `audit.events`. No SLF4J root-config gymnastics required.

---

## See also

- [`docs/auth.md`](auth.md) — the use cases that emit audit events
- [`docs/rate-limiting.md`](rate-limiting.md) — companion security control
