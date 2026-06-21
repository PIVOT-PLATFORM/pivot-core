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

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(User user, Tenant tenant, String eventType, String ip, String userAgent, String meta) {
        repo.save(AuditEvent.of(user, tenant, eventType, ip, userAgent, meta));
    }

    public void log(User user, String eventType, String ip, String userAgent) {
        self.log(user, user != null ? user.getTenant() : null, eventType, ip, userAgent, null);
    }

    // Event type constants
    public static final String REGISTER = "auth.register";
    public static final String LOGIN = "auth.login";
    public static final String LOGIN_FAILED = "auth.login_failed";
    public static final String LOGOUT = "auth.logout";
    public static final String EMAIL_VERIFIED = "auth.email_verified";
    public static final String PASSWORD_RESET_REQUEST = "auth.password_reset_request";
    public static final String PASSWORD_RESET = "auth.password_reset";
    public static final String GOOGLE_LINKED = "auth.google_linked";
    public static final String DEVICE_OTP_SENT = "auth.device_otp_sent";
    public static final String DEVICE_VERIFIED = "auth.device_verified";
    public static final String DEVICE_OTP_FAILED = "auth.device_otp_failed";
    public static final String OIDC_LOGIN = "auth.oidc_login";
}
