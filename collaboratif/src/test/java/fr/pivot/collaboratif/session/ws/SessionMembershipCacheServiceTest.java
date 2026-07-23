package fr.pivot.collaboratif.session.ws;

import fr.pivot.collaboratif.session.ParticipantRepository;
import fr.pivot.collaboratif.session.Session;
import fr.pivot.collaboratif.session.SessionRepository;
import fr.pivot.collaboratif.session.SessionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SessionMembershipCacheService} (US19.1.2 EN19.2) — no test existed for
 * this class before this PR. Covers the cache hit/miss paths, the tenant-isolation check
 * ({@link SessionMembershipCacheService#isMember} must return {@code false}, not leak, for a
 * sessionId that collides across tenants) and the participation check, plus that a cache miss
 * always writes back to Redis with the 5s TTL regardless of the outcome.
 */
@ExtendWith(MockitoExtension.class)
class SessionMembershipCacheServiceTest {

    private static final Long TENANT_ID = 100L;
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final Long USER_ID = 42L;
    private static final String CACHE_KEY = "ws:session-auth:" + TENANT_ID + ":" + SESSION_ID + ":" + USER_ID;

    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private ParticipantRepository participantRepository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private SessionMembershipCacheService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new SessionMembershipCacheService(sessionRepository, participantRepository, redisTemplate);
    }

    private Session sessionOwnedByTenant(final Long tenantId) {
        return new Session(tenantId, null, "T", SessionType.POLL, "ABCDEF", "{}", 10L, Instant.now());
    }

    @Test
    void isMemberReturnsTrueOnACachedMemberHitWithoutHittingTheDatabase() {
        when(valueOperations.get(CACHE_KEY)).thenReturn("1");

        boolean member = service.isMember(TENANT_ID, SESSION_ID, USER_ID);

        assertThat(member).isTrue();
        verify(sessionRepository, never()).findById(any());
        verify(participantRepository, never()).existsBySessionIdAndUserId(any(), any());
    }

    @Test
    void isMemberReturnsFalseOnACachedNonMemberHitWithoutHittingTheDatabase() {
        when(valueOperations.get(CACHE_KEY)).thenReturn("0");

        boolean member = service.isMember(TENANT_ID, SESSION_ID, USER_ID);

        assertThat(member).isFalse();
        verify(sessionRepository, never()).findById(any());
    }

    @Test
    void isMemberOnACacheMissLooksUpAndCachesATruePositiveWithTheFiveSecondTtl() {
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        Session session = sessionOwnedByTenant(TENANT_ID);
        ReflectionTestUtils.setField(session, "id", SESSION_ID);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(participantRepository.existsBySessionIdAndUserId(SESSION_ID, USER_ID)).thenReturn(true);

        boolean member = service.isMember(TENANT_ID, SESSION_ID, USER_ID);

        assertThat(member).isTrue();
        verify(valueOperations).set(eq(CACHE_KEY), eq("1"), eq(Duration.ofSeconds(5)));
    }

    @Test
    void isMemberOnACacheMissReturnsFalseAndCachesItWhenTheSessionDoesNotExist() {
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        boolean member = service.isMember(TENANT_ID, SESSION_ID, USER_ID);

        assertThat(member).isFalse();
        verify(valueOperations).set(eq(CACHE_KEY), eq("0"), eq(Duration.ofSeconds(5)));
        verify(participantRepository, never()).existsBySessionIdAndUserId(any(), any());
    }

    /**
     * A sessionId belonging to a <em>different</em> tenant than the requesting user must be
     * treated as non-existent — this is the cross-tenant IDOR guard called out by this repo's
     * {@code CLAUDE.md} tenant-isolation rule. Critically, the participant repository must never
     * even be consulted in this case: doing so on a session id that exists in another tenant
     * could otherwise leak whether *some* participant row exists for that id.
     */
    @Test
    void isMemberReturnsFalseWhenTheSessionBelongsToADifferentTenant() {
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        Session foreignSession = sessionOwnedByTenant(999L);
        ReflectionTestUtils.setField(foreignSession, "id", SESSION_ID);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(foreignSession));

        boolean member = service.isMember(TENANT_ID, SESSION_ID, USER_ID);

        assertThat(member).isFalse();
        verify(participantRepository, never()).existsBySessionIdAndUserId(any(), any());
        verify(valueOperations).set(eq(CACHE_KEY), eq("0"), eq(Duration.ofSeconds(5)));
    }

    @Test
    void isMemberOnACacheMissReturnsFalseAndCachesItWhenTheUserNeverJoined() {
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        Session session = sessionOwnedByTenant(TENANT_ID);
        ReflectionTestUtils.setField(session, "id", SESSION_ID);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(participantRepository.existsBySessionIdAndUserId(SESSION_ID, USER_ID)).thenReturn(false);

        boolean member = service.isMember(TENANT_ID, SESSION_ID, USER_ID);

        assertThat(member).isFalse();
        verify(valueOperations).set(eq(CACHE_KEY), eq("0"), eq(Duration.ofSeconds(5)));
    }
}
