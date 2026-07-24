package fr.pivot.collaboratif.session.ws;

import fr.pivot.collaboratif.session.ParticipantRepository;
import fr.pivot.collaboratif.session.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Caches Module Session participation decisions for STOMP SUBSCRIBE authorization (US19.1.2
 * EN19.2), mirroring {@link fr.pivot.collaboratif.whiteboard.ws.MembershipCacheService}'s
 * pattern for the {@code session} destination family.
 *
 * <p>Auth cache key {@code ws:session-auth:{tenantId}:{sessionId}:{userId}}, 5 s TTL — same
 * revocation-SLA reasoning as the whiteboard channel.
 */
@Service
public class SessionMembershipCacheService {

    private static final Logger LOG = LoggerFactory.getLogger(SessionMembershipCacheService.class);
    private static final Duration AUTH_TTL = Duration.ofSeconds(5);
    private static final String AUTH_PREFIX = "ws:session-auth:";
    private static final String MEMBER_VALUE = "1";
    private static final String NON_MEMBER_VALUE = "0";

    private final SessionRepository sessionRepository;
    private final ParticipantRepository participantRepository;
    private final StringRedisTemplate redisTemplate;

    /**
     * Creates the service with the required dependencies.
     *
     * @param sessionRepository     repository for tenant-isolation checks
     * @param participantRepository repository for participation lookups
     * @param redisTemplate         Redis client for caching
     */
    public SessionMembershipCacheService(
            final SessionRepository sessionRepository,
            final ParticipantRepository participantRepository,
            final StringRedisTemplate redisTemplate) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Returns {@code true} when the given authenticated user has joined the given session as a
     * participant within the given tenant.
     *
     * <p>A session belonging to a different tenant is treated as non-existent (returns
     * {@code false}) so sessionId collisions across tenants do not reveal cross-tenant data.
     *
     * @param tenantId  the requesting user's tenant's {@code public.tenants.id}
     * @param sessionId the session UUID
     * @param userId    the requesting user's {@code public.users.id}
     * @return {@code true} if the user is a participant of the session in that tenant
     */
    public boolean isMember(final Long tenantId, final UUID sessionId, final Long userId) {
        String key = AUTH_PREFIX + tenantId + ":" + sessionId + ":" + userId;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return MEMBER_VALUE.equals(cached);
        }
        boolean member = lookupMembership(tenantId, sessionId, userId);
        redisTemplate.opsForValue().set(key, member ? MEMBER_VALUE : NON_MEMBER_VALUE, AUTH_TTL);
        LOG.debug(
                "Session membership cache miss: user={} session={} tenant={} result={}",
                userId, sessionId, tenantId, member);
        return member;
    }

    private boolean lookupMembership(final Long tenantId, final UUID sessionId, final Long userId) {
        boolean sessionOwnedByTenant = sessionRepository.findById(sessionId)
                .map(session -> session.getTenantId().equals(tenantId))
                .orElse(false);
        if (!sessionOwnedByTenant) {
            return false;
        }
        return participantRepository.existsBySessionIdAndUserId(sessionId, userId);
    }
}
