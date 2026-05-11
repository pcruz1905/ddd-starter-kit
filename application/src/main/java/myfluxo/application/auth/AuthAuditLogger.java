package myfluxo.application.auth;

import jakarta.inject.Singleton;
import myfluxo.domain.auth.model.RefreshTokenFamilyId;
import myfluxo.domain.shared.model.Email;
import myfluxo.domain.users.model.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structured audit log for auth events.
 *
 * <p>Routes every entry through a dedicated logger
 * ({@code myfluxo.audit.auth}) so production deployments can pipe this
 * channel to a separate sink (SIEM, security-events bucket, etc.)
 * independently of regular application logs.
 *
 * <h2>What we record</h2>
 * <ul>
 *     <li><b>Success events</b> (info-level): REGISTERED, LOGIN_SUCCESS,
 *         REFRESHED, LOGGED_OUT, PASSWORD_CHANGED.</li>
 *     <li><b>Suspicious events</b> (warn-level): LOGIN_FAILURE,
 *         REFRESH_REUSE_DETECTED.</li>
 * </ul>
 *
 * <h2>What we DON'T record</h2>
 * Plaintext credentials, tokens, hashes. The audit log is sensitive
 * and may be widely shipped — keep the highest-sensitivity material
 * out by construction.
 *
 * <p>Subclassable so tests can inspect events without touching the
 * SLF4J root config.
 */
@Singleton
public class AuthAuditLogger {

    private static final Logger LOG = LoggerFactory.getLogger("myfluxo.audit.auth");

    public void registered(UserId userId, Email email) {
        LOG.info("REGISTERED userId={} email={}", userId.value(), email.value());
    }

    public void loginSuccess(UserId userId, Email email) {
        LOG.info("LOGIN_SUCCESS userId={} email={}", userId.value(), email.value());
    }

    /**
     * Failure variant — DO NOT log the password, hash, or any token.
     * The attempted email is included for support / abuse correlation.
     */
    public void loginFailure(String attemptedEmail, String reason) {
        LOG.warn("LOGIN_FAILURE email={} reason={}",
            attemptedEmail == null ? "" : attemptedEmail,
            reason);
    }

    public void refreshed(UserId userId) {
        LOG.info("REFRESHED userId={}", userId.value());
    }

    public void refreshReuseDetected(UserId userId, RefreshTokenFamilyId familyId) {
        LOG.warn("REFRESH_REUSE_DETECTED userId={} familyId={}",
            userId.value(), familyId.value());
    }

    public void loggedOut(UserId userId) {
        LOG.info("LOGGED_OUT userId={}", userId.value());
    }

    public void passwordChanged(UserId userId) {
        LOG.info("PASSWORD_CHANGED userId={}", userId.value());
    }
}
