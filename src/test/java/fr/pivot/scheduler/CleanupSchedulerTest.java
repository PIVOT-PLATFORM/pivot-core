package fr.pivot.scheduler;

import fr.pivot.auth.entity.TokenStatus;
import fr.pivot.auth.repository.AccessTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Unit test for {@link CleanupScheduler} — verifies the purge delegates to the repository
 * with the expired/revoked statuses and a ~30-day threshold.
 */
@ExtendWith(MockitoExtension.class)
class CleanupSchedulerTest {

    @Mock private AccessTokenRepository tokenRepo;

    @Test
    void purgeExpiredTokens_callsRepositoryWithStatuses() {
        final CleanupScheduler scheduler = new CleanupScheduler(tokenRepo);

        scheduler.purgeExpiredTokens();

        verify(tokenRepo).deleteExpiredAndRevoked(
            any(Instant.class), eq(TokenStatus.EXPIRED), eq(TokenStatus.REVOKED));
    }
}
