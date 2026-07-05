package fr.pivot.account.scheduler;

import fr.pivot.account.service.AccountDeletionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled purge (anonymization) of accounts whose RGPD Art. 17 grace period has elapsed
 * (US02.2.4). Reuses the {@code @EnableScheduling} already declared on {@code AppConfig} for
 * {@link fr.pivot.scheduler.CleanupScheduler} / {@link
 * fr.pivot.account.scheduler.ExportCleanupScheduler} — no second scheduling mechanism.
 */
@Component
public class AccountDeletionScheduler {

    private final AccountDeletionService accountDeletionService;

    /**
     * Constructs the scheduler with its collaborator.
     *
     * @param accountDeletionService owns the anonymization business logic
     */
    public AccountDeletionScheduler(final AccountDeletionService accountDeletionService) {
        this.accountDeletionService = accountDeletionService;
    }

    /**
     * Anonymizes every account past its grace period.
     *
     * <p>Schedule is configurable via {@code pivot.account-deletion.purge-cron} (default:
     * nightly at 02:15 — offset from {@code CleanupScheduler}'s 02:00 token purge and {@code
     * ExportCleanupScheduler}'s 02:30 export purge to avoid contention).
     */
    @Scheduled(cron = "${pivot.account-deletion.purge-cron:0 15 2 * * *}")
    public void purgeDueAccounts() {
        accountDeletionService.anonymizeDueAccounts();
    }
}
