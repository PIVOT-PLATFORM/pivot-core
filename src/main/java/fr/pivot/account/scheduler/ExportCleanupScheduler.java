package fr.pivot.account.scheduler;

import fr.pivot.account.entity.DataExportRequest;
import fr.pivot.account.entity.DataExportStatus;
import fr.pivot.account.repository.DataExportRequestRepository;
import fr.pivot.account.service.ExportStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Scheduled purge of expired data-export archives (RGPD data minimisation, US02.3.1).
 *
 * <p>Once the 24h download-link TTL elapses, the ZIP file is deleted from disk and its
 * tracking row is removed — mirrors {@link fr.pivot.scheduler.CleanupScheduler} for expired
 * session tokens.
 */
@Component
public class ExportCleanupScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ExportCleanupScheduler.class);

    private final DataExportRequestRepository exportRepo;
    private final ExportStorageService storageService;

    public ExportCleanupScheduler(final DataExportRequestRepository exportRepo,
                                   final ExportStorageService storageService) {
        this.exportRepo = exportRepo;
        this.storageService = storageService;
    }

    /**
     * Deletes {@code READY} requests whose {@code expires_at} is in the past, along with their
     * archive file on disk.
     *
     * <p>Schedule is configurable via {@code pivot.export.cleanup-cron} (default: nightly at 02:30,
     * offset from {@code CleanupScheduler}'s 02:00 token purge to avoid contention).
     */
    @Scheduled(cron = "${pivot.export.cleanup-cron:0 30 2 * * *}")
    @Transactional
    public void purgeExpiredExports() {
        final List<DataExportRequest> expired =
            exportRepo.findByStatusAndExpiresAtBefore(DataExportStatus.READY, Instant.now());
        if (expired.isEmpty()) {
            return;
        }
        LOG.info("event=EXPORT_CLEANUP_START count={}", expired.size());
        expired.forEach(req -> storageService.delete(req.getFilePath()));
        exportRepo.deleteAll(expired);
        LOG.info("event=EXPORT_CLEANUP_DONE count={}", expired.size());
    }
}
