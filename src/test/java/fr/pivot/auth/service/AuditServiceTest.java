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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    void log_convenience_registersAfterCommit_whenInActiveTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.log(user, AuditService.REGISTER, "2.3.4.5", "ua");

        // Invoke the registered afterCommit hook (simulates transaction commit)
        new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
            .forEach(s -> s.afterCommit());

        verify(self).log(eq(user), eq(tenant), eq(AuditService.REGISTER), eq("2.3.4.5"), eq("ua"), isNull());
    }

    @Test
    void log_convenience_handlesNullUser() {
        service.log(null, AuditService.LOGIN_FAILED, "3.4.5.6", "ua");
        verify(self).log(isNull(), isNull(), eq(AuditService.LOGIN_FAILED), eq("3.4.5.6"), eq("ua"), isNull());
    }
}
