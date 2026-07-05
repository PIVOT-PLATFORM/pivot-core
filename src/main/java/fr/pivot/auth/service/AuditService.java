package fr.pivot.auth.service;

import fr.pivot.auth.entity.AuditEvent;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.AuditEventRepository;
import fr.pivot.tenant.entity.Tenant;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Persists security/auth audit events ({@code login}, {@code logout}, {@code register}…).
 *
 * <p>Writes run asynchronously in their own {@code REQUIRES_NEW} transaction so an audit
 * failure never impacts the latency or outcome of the originating auth flow. The async
 * dispatch requires {@code @EnableAsync} (declared on {@link fr.pivot.config.AppConfig}) and
 * is reached through the Spring proxy via the self-injected {@link #self} reference.
 *
 * <p>When called from within an active transaction, the {@code log(User, String, String,
 * String)} overload defers dispatch until that transaction completes — on <strong>both</strong>
 * commit and rollback (see its JavaDoc) — so failure events (wrong password, rate limit, bad
 * credentials) are still recorded even though the method that logs them typically throws right
 * afterwards and rolls back its own transaction.
 */
@Service
public class AuditService {

    private final AuditEventRepository repo;

    /**
     * Self-reference (proxied) used to invoke the {@code @Async @Transactional} overload
     * through the Spring proxy. A direct {@code this.log(...)} call would bypass the proxy,
     * disabling the async dispatch and the {@code REQUIRES_NEW} transaction (Sonar S2229/S6809).
     */
    private final AuditService self;

    public AuditService(final AuditEventRepository repo, final @Lazy AuditService self) {
        this.repo = repo;
        this.self = self;
    }

    /**
     * Persists an audit event asynchronously in a new transaction.
     *
     * @param user      the subject user (may be {@code null} for failed logins on unknown emails)
     * @param tenant    the tenant the event belongs to (may be {@code null})
     * @param eventType one of the {@code AuditService} event-type constants
     * @param ip        client IP
     * @param userAgent client user-agent
     * @param meta      optional free-form metadata, or {@code null}
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(User user, Tenant tenant, String eventType, String ip, String userAgent, String meta) {
        repo.save(AuditEvent.of(user, tenant, eventType, ip, userAgent, meta));
    }

    /**
     * Convenience overload deriving the tenant from the user. Routes through the proxy
     * ({@link #self}) so the async + {@code REQUIRES_NEW} semantics actually apply.
     *
     * <p>Dispatch is deferred to {@link TransactionSynchronization#afterCompletion(int)} rather
     * than {@code afterCommit()} — deliberately, so the event is still persisted when the
     * caller's surrounding transaction rolls back. Several call sites (e.g. {@code LOGIN_FAILED},
     * {@code CHANGE_PASSWORD_FAILED}) log the event and then immediately throw, which marks the
     * enclosing {@code @Transactional} method for rollback; {@code afterCommit()} never fires on
     * that path, so a failure-audit trail must not depend on it.
     *
     * @param user      the subject user (may be {@code null})
     * @param eventType one of the event-type constants
     * @param ip        client IP
     * @param userAgent client user-agent
     */
    public void log(User user, String eventType, String ip, String userAgent) {
        log(user, user != null ? user.getTenant() : null, eventType, ip, userAgent);
    }

    /**
     * Convenience overload for an explicit tenant that is not (necessarily) the caller's own
     * tenant — e.g. a super admin action performed against a tenant other than their own.
     *
     * <p>Defers the actual write until after the enclosing transaction commits, exactly like
     * {@link #log(User, String, String, String)}. This matters when {@code tenant} (or {@code
     * user}) was itself only just inserted in the same still-open transaction: the {@code
     * REQUIRES_NEW} write below runs on a separate connection/transaction and would otherwise
     * fail to see that uncommitted row (foreign key not yet visible), or — for a row visible
     * across transactions in READ COMMITTED — simply audit an operation that later rolls back.
     *
     * @param user      the acting user (may be {@code null})
     * @param tenant    the tenant the event is about (may be {@code null}, and may differ from
     *                  {@code user.getTenant()})
     * @param eventType one of the event-type constants
     * @param ip        client IP
     * @param userAgent client user-agent
     */
    public void log(User user, Tenant tenant, String eventType, String ip, String userAgent) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    self.log(user, tenant, eventType, ip, userAgent, null);
                }
            });
        } else {
            self.log(user, tenant, eventType, ip, userAgent, null);
        }
    }

    // Event type constants
    public static final String REGISTER = "auth.register";
    public static final String LOGIN = "auth.login";
    public static final String LOGIN_FAILED = "auth.login_failed";
    public static final String LOGOUT = "auth.logout";
    public static final String EMAIL_VERIFIED = "auth.email_verified";
    public static final String PASSWORD_RESET_REQUEST = "auth.password_reset_request";
    public static final String PASSWORD_RESET = "auth.password_reset";
    public static final String CHANGE_PASSWORD = "auth.change_password";
    public static final String CHANGE_PASSWORD_FAILED = "auth.change_password_failed";
    public static final String GOOGLE_LINKED = "auth.google_linked";
    public static final String DEVICE_OTP_SENT = "auth.device_otp_sent";
    public static final String DEVICE_VERIFIED = "auth.device_verified";
    public static final String DEVICE_OTP_FAILED = "auth.device_otp_failed";
    public static final String OIDC_LOGIN = "auth.oidc_login";
    public static final String EMAIL_CHANGE_REQUESTED = "auth.email_change_requested";
    public static final String EMAIL_CHANGE_DUPLICATE_ATTEMPT = "auth.email_change_duplicate_attempt";
    public static final String EMAIL_CHANGE_CONFIRMED = "auth.email_change_confirmed";
    public static final String EMAIL_CHANGE_TARGET_TAKEN = "auth.email_change_target_taken";
    public static final String MODULE_ACTIVATED = "module.activated";
    public static final String MODULE_DEACTIVATED = "module.deactivated";
    public static final String PROFILE_UPDATED = "account.profile_updated";
    public static final String AVATAR_UPDATED = "account.avatar_updated";
    /** RGPD Art. 20 — logged when a user requests a personal-data export (US02.3.1). */
    public static final String DATA_EXPORT_REQUESTED = "account.data_export_requested";
    public static final String TENANT_CREATED = "tenant.created";
    public static final String TENANT_CREATION_RATE_LIMIT_EXCEEDED = "tenant.creation_rate_limit_exceeded";

    /** Audit event US06.2.2 — a super admin deactivated a tenant (bulk session revocation). */
    public static final String TENANT_DEACTIVATED = "tenant.deactivated";
}
