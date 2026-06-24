package fr.pivot.scheduler;

import fr.pivot.auth.entity.TokenStatus;
import fr.pivot.auth.repository.AccessTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Scheduled cleanup of expired and revoked session tokens.
 *
 * <p>Runs nightly by default (configurable via {@code pivot.cleanup.tokens-cron}).
 * Retains revoked tokens for 30 days for audit forensics before deletion.
 */
@Component
public class CleanupScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(CleanupScheduler.class);

    private final AccessTokenRepository tokenRepo;

    public CleanupScheduler(final AccessTokenRepository tokenRepo) {
        this.tokenRepo = tokenRepo;
    }

    /**
     * Purges tokens expired or revoked more than 30 days ago.
     *
     * <p>Schedule is configurable via {@code pivot.cleanup.tokens-cron} (default: nightly at 02:00).
     */
    @Scheduled(cron = "${pivot.cleanup.tokens-cron:0 0 2 * * *}")
    @Transactional
    public void purgeExpiredTokens() {
        final Instant threshold = Instant.now().minus(30, ChronoUnit.DAYS);
        LOG.info("event=CLEANUP_TOKENS_START threshold={}", threshold);
        tokenRepo.deleteExpiredAndRevoked(threshold, TokenStatus.EXPIRED, TokenStatus.REVOKED);
        LOG.info("event=CLEANUP_TOKENS_DONE");
    }
}
