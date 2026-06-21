package fr.pivot.auth.service;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.auth.entity.AccessToken;
import fr.pivot.auth.entity.AuthMethod;
import fr.pivot.auth.entity.TokenStatus;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.AccessTokenRepository;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.util.CryptoUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link TokenService} against a real PostgreSQL instance (US-AUTH-002).
 *
 * <p>Uses a Testcontainers PostgreSQL 18 container (started by {@link fr.pivot.AbstractIntegrationTest}).
 * Flyway applies {@code db/migration} + {@code db/seeds} on startup; {@code user@pivot.test}
 * (tenant_id=1) is seeded by {@code V2__test_seeds.sql}.
 *
 * <p>No {@code @Transactional} on the class — service methods commit their own transactions
 * so assertions reflect the real persisted state.
 * {@code @AfterEach} deletes only tokens belonging to the test user (not a global deleteAll).
 */
class TokenServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TokenService tokenService;

    @Autowired
    private AccessTokenRepository tokenRepo;

    @Autowired
    private UserRepository userRepo;

    private User testUser;

    @BeforeEach
    void setUp() {
        // Seeded by V2__test_seeds.sql (Flyway, profile test, tenant_id=1)
        testUser = userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(1L, "user@pivot.test")
            .orElseThrow(() -> new IllegalStateException(
                "Test user 'user@pivot.test' not found — verify Flyway applied db/seeds (profile test)"));
    }

    @AfterEach
    void cleanUp() {
        tokenRepo.deleteByUserId(testUser.getId());
    }

    // ----------------------------------------------------------------
    // issue()
    // ----------------------------------------------------------------

    @Test
    void issue_savesHashedToken_andRawTokenIsValid() {
        final TokenService.TokenIssueResult result = tokenService.issue(
            testUser, "fp-test", "Chrome", "ua", "127.0.0.1", AuthMethod.PASSWORD, false);

        assertThat(result.rawToken()).isNotBlank();
        // 32 bytes of SecureRandom → 64 hex characters
        assertThat(result.rawToken()).hasSize(64);

        final Optional<AccessToken> found = tokenRepo
            .findByTokenHashAndStatus(CryptoUtils.sha256(result.rawToken()), TokenStatus.ACTIVE);
        assertThat(found).isPresent();
        // The stored hash must match SHA-256(rawToken) and must NOT equal the raw token
        assertThat(found.get().getTokenHash()).isEqualTo(CryptoUtils.sha256(result.rawToken()));
        assertThat(found.get().getTokenHash()).isNotEqualTo(result.rawToken());
    }

    @Test
    void issue_setsCorrectMetadata_inDb() {
        final TokenService.TokenIssueResult result = tokenService.issue(
            testUser, "fp-device", "Firefox", "Mozilla/5.0", "192.168.1.1", AuthMethod.GOOGLE, true);

        final AccessToken saved = tokenRepo
            .findByTokenHashAndStatus(CryptoUtils.sha256(result.rawToken()), TokenStatus.ACTIVE)
            .orElseThrow();

        assertThat(saved.getDeviceFingerprint()).isEqualTo("fp-device");
        assertThat(saved.getDeviceName()).isEqualTo("Firefox");
        assertThat(saved.getAuthMethod()).isEqualTo(AuthMethod.GOOGLE);
        assertThat(saved.isRememberMe()).isTrue();
        // rememberMe TTL: 2 592 000 s (30 days) — default from feature_flags fallback
        assertThat(saved.getTtlSeconds()).isEqualTo(2_592_000);
        assertThat(saved.getExpiresAt()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    // ----------------------------------------------------------------
    // validate()
    // ----------------------------------------------------------------

    @Test
    void validate_returnsToken_andUpdatesLastUsedAt() {
        final TokenService.TokenIssueResult issued = tokenService.issue(
            testUser, null, null, "ua", "127.0.0.1", AuthMethod.PASSWORD, false);

        final Optional<AccessToken> validated = tokenService.validate(issued.rawToken());

        assertThat(validated).isPresent();
        assertThat(validated.get().getLastUsedAt()).isNotNull();
    }

    @Test
    void validate_returnsEmpty_forUnknownToken() {
        final Optional<AccessToken> result = tokenService.validate("0000000000000000000000000000000000000000000000000000000000000000");
        assertThat(result).isEmpty();
    }

    @Test
    void validate_returnsEmpty_forNullToken() {
        assertThat(tokenService.validate(null)).isEmpty();
    }

    // ----------------------------------------------------------------
    // revokeByRawToken()
    // ----------------------------------------------------------------

    @Test
    void revokeByRawToken_setsStatusRevoked_inDb() {
        final TokenService.TokenIssueResult issued = tokenService.issue(
            testUser, null, null, "ua", "127.0.0.1", AuthMethod.PASSWORD, false);

        tokenService.revokeByRawToken(issued.rawToken());

        // Token must no longer be findable as ACTIVE
        final Optional<AccessToken> active = tokenRepo
            .findByTokenHashAndStatus(CryptoUtils.sha256(issued.rawToken()), TokenStatus.ACTIVE);
        assertThat(active).isEmpty();

        // Token must now exist with REVOKED status
        final Optional<AccessToken> revoked = tokenRepo
            .findByTokenHashAndStatus(CryptoUtils.sha256(issued.rawToken()), TokenStatus.REVOKED);
        assertThat(revoked).isPresent();
        assertThat(revoked.get().getRevokedAt()).isNotNull();
    }

    @Test
    void revokeByRawToken_isNoOp_forNull() {
        // Must not throw and leave DB untouched
        tokenService.revokeByRawToken(null);
        assertThat(tokenRepo.count()).isZero();
    }

    // ----------------------------------------------------------------
    // rotate()
    // ----------------------------------------------------------------

    @Test
    void rotate_revokesOldAndIssuesNew_inDb() {
        final TokenService.TokenIssueResult first = tokenService.issue(
            testUser, "fp-1", "Chrome", "ua", "127.0.0.1", AuthMethod.PASSWORD, false);

        final AccessToken firstToken = tokenRepo
            .findByTokenHashAndStatus(CryptoUtils.sha256(first.rawToken()), TokenStatus.ACTIVE)
            .orElseThrow();

        final Optional<TokenService.TokenIssueResult> rotated = tokenService.rotate(firstToken);

        assertThat(rotated).isPresent();
        assertThat(rotated.get().rawToken()).isNotEqualTo(first.rawToken());

        // Old token must be REVOKED in DB
        final AccessToken old = tokenRepo.findById(firstToken.getId()).orElseThrow();
        assertThat(old.getStatus()).isEqualTo(TokenStatus.REVOKED);
        assertThat(old.getRevokedAt()).isNotNull();

        // New token must be ACTIVE in DB
        final Optional<AccessToken> newToken = tokenRepo
            .findByTokenHashAndStatus(CryptoUtils.sha256(rotated.get().rawToken()), TokenStatus.ACTIVE);
        assertThat(newToken).isPresent();
    }

    @Test
    void rotate_inheritsDeviceMetadata_inNewToken() {
        final TokenService.TokenIssueResult first = tokenService.issue(
            testUser, "fp-inherit", "Safari", "ua", "10.0.0.1", AuthMethod.GOOGLE, false);

        final AccessToken firstToken = tokenRepo
            .findByTokenHashAndStatus(CryptoUtils.sha256(first.rawToken()), TokenStatus.ACTIVE)
            .orElseThrow();

        final TokenService.TokenIssueResult rotated = tokenService.rotate(firstToken).orElseThrow();

        final AccessToken newToken = tokenRepo
            .findByTokenHashAndStatus(CryptoUtils.sha256(rotated.rawToken()), TokenStatus.ACTIVE)
            .orElseThrow();

        assertThat(newToken.getDeviceFingerprint()).isEqualTo("fp-inherit");
        assertThat(newToken.getDeviceName()).isEqualTo("Safari");
        assertThat(newToken.getAuthMethod()).isEqualTo(AuthMethod.GOOGLE);
        assertThat(newToken.getIpAddress()).isEqualTo("10.0.0.1");
    }

    // ----------------------------------------------------------------
    // TokenStatus AttributeConverter round-trip
    // ----------------------------------------------------------------

    @Test
    void statusConverter_roundtrip_activeInDb() {
        // Verifies that TokenStatusConverter stores 'active' (lowercase) in the DB
        // and retrieves it correctly as the ACTIVE enum value.
        final TokenService.TokenIssueResult issued = tokenService.issue(
            testUser, null, null, "ua", "127.0.0.1", AuthMethod.PASSWORD, false);

        final AccessToken found = tokenRepo
            .findByTokenHashAndStatus(CryptoUtils.sha256(issued.rawToken()), TokenStatus.ACTIVE)
            .orElseThrow();

        assertThat(found.getStatus()).isEqualTo(TokenStatus.ACTIVE);
    }
}
