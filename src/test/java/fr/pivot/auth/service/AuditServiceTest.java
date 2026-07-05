package fr.pivot.auth.service;

import fr.pivot.auth.entity.AuditEvent;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.AuditEventRepository;
import fr.pivot.tenant.entity.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuditService} — async event persistence and transaction-aware dispatch.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditServiceTest {

    @Mock private AuditEventRepository repo;
    @Mock private AuditService self;
    @Mock private User user;
    @Mock private Tenant tenant;
    @Mock private Tenant otherTenant;

    private AuditService service;

    @BeforeEach
    void setUp() {
        service = new AuditService(repo, self);
        when(user.getTenant()).thenReturn(tenant);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void log_async_persistsAuditEvent() {
        service.log(user, tenant, AuditService.LOGIN, "1.2.3.4", "Mozilla/5.0", null);
        verify(repo).save(any(AuditEvent.class));
    }

    @Test
    void log_convenience_callsSelfDirectly_whenNoActiveTransaction() {
        service.log(user, AuditService.LOGIN, "1.2.3.4", "ua");
        verify(self).log(eq(user), eq(tenant), eq(AuditService.LOGIN), eq("1.2.3.4"), eq("ua"), isNull());
    }

    @Test
    void log_convenience_dispatchesOnCompletion_whenSurroundingTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.log(user, AuditService.REGISTER, "2.3.4.5", "ua");

        // Invoke the registered afterCompletion hook (simulates transaction commit)
        new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
            .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        verify(self).log(eq(user), eq(tenant), eq(AuditService.REGISTER), eq("2.3.4.5"), eq("ua"), isNull());
    }

    @Test
    void log_convenience_dispatchesOnCompletion_whenSurroundingTransactionRollsBack() {
        // Regression test: a failure event (e.g. LOGIN_FAILED, CHANGE_PASSWORD_FAILED) is logged
        // by a @Transactional method that then throws, rolling back its own transaction —
        // afterCommit() would never fire on that path, so the event must not depend on it.
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.log(user, AuditService.CHANGE_PASSWORD_FAILED, "6.7.8.9", "ua");

        new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
            .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(self).log(
            eq(user), eq(tenant), eq(AuditService.CHANGE_PASSWORD_FAILED), eq("6.7.8.9"), eq("ua"), isNull());
    }

    @Test
    void log_convenience_handlesNullUser() {
        service.log(null, AuditService.LOGIN_FAILED, "3.4.5.6", "ua");
        verify(self).log(isNull(), isNull(), eq(AuditService.LOGIN_FAILED), eq("3.4.5.6"), eq("ua"), isNull());
    }

    // ----------------------------------------------------------------
    // log(User, Tenant, eventType, ip, userAgent) — explicit-tenant overload (US06.2.1)
    // ----------------------------------------------------------------

    @Test
    void log_explicitTenant_usesGivenTenant_notUsersOwnTenant_whenNoActiveTransaction() {
        service.log(user, otherTenant, AuditService.TENANT_CREATED, "1.2.3.4", "ua");

        // otherTenant, not user.getTenant() (== tenant) — the whole point of this overload.
        verify(self).log(eq(user), eq(otherTenant), eq(AuditService.TENANT_CREATED), eq("1.2.3.4"), eq("ua"), isNull());
    }

    @Test
    void log_explicitTenant_registersAfterCommit_whenInActiveTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.log(user, otherTenant, AuditService.TENANT_CREATED, "2.3.4.5", "ua");

        verify(self, never()).log(any(), any(), any(), any(), any(), any());

        // Invoke the registered afterCommit hook (simulates transaction commit) — this is what
        // lets a REQUIRES_NEW write see a row (here, otherTenant) inserted earlier in the same,
        // still-open enclosing transaction.
        new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
            .forEach(s -> s.afterCommit());

        verify(self).log(eq(user), eq(otherTenant), eq(AuditService.TENANT_CREATED), eq("2.3.4.5"), eq("ua"), isNull());
    }
}
