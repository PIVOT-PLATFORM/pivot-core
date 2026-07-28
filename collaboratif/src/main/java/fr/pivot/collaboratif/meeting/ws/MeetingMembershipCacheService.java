package fr.pivot.collaboratif.meeting.ws;

import fr.pivot.collaboratif.meeting.Meeting;
import fr.pivot.collaboratif.meeting.MeetingRepository;
import fr.pivot.core.team.TeamMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Caches MeetOps meeting membership decisions for STOMP SUBSCRIBE authorization (US12.2.1
 * AC-S3), mirroring {@code fr.pivot.collaboratif.session.ws.SessionMembershipCacheService}'s
 * pattern for the {@code meeting} destination family.
 *
 * <p>"Member" here means exactly {@code MeetingAccessService#resolveMeetingForCaller}'s own
 * visibility rule (the meeting's owner, or any member of its optional team) — kept independent
 * (not delegating to that service) so this class stays a pure repository-backed lookup with no
 * dependency on {@code CollaboratifRequestPrincipal}/exception-throwing semantics, matching {@code
 * SessionMembershipCacheService}'s equally independent shape.
 *
 * <p>Auth cache key {@code ws:meeting-auth:{tenantId}:{meetingId}:{userId}}, 5 s TTL — same
 * revocation-SLA reasoning as the session/whiteboard channels.
 */
@Service
public class MeetingMembershipCacheService {

    private static final Logger LOG = LoggerFactory.getLogger(MeetingMembershipCacheService.class);
    private static final Duration AUTH_TTL = Duration.ofSeconds(5);
    private static final String AUTH_PREFIX = "ws:meeting-auth:";
    private static final String MEMBER_VALUE = "1";
    private static final String NON_MEMBER_VALUE = "0";

    private final MeetingRepository meetingRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final StringRedisTemplate redisTemplate;

    /**
     * Creates the service with the required dependencies.
     *
     * @param meetingRepository     repository for tenant-isolation and owner checks
     * @param teamMemberRepository  repository for team-membership lookups
     * @param redisTemplate         Redis client for caching
     */
    public MeetingMembershipCacheService(
            final MeetingRepository meetingRepository,
            final TeamMemberRepository teamMemberRepository,
            final StringRedisTemplate redisTemplate) {
        this.meetingRepository = meetingRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Returns {@code true} when the given authenticated user has visibility into the given
     * meeting within the given tenant — its owner, or a member of its optional team.
     *
     * <p>A meeting belonging to a different tenant is treated as non-existent (returns {@code
     * false}) so meetingId collisions across tenants do not reveal cross-tenant data (AC-S1/AC-S3).
     *
     * @param tenantId  the requesting user's tenant's {@code public.tenants.id}
     * @param meetingId the meeting UUID
     * @param userId    the requesting user's {@code public.users.id}
     * @return {@code true} if the user is a member of the meeting in that tenant
     */
    public boolean isMember(final Long tenantId, final UUID meetingId, final Long userId) {
        String key = AUTH_PREFIX + tenantId + ":" + meetingId + ":" + userId;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return MEMBER_VALUE.equals(cached);
        }
        boolean member = lookupMembership(tenantId, meetingId, userId);
        redisTemplate.opsForValue().set(key, member ? MEMBER_VALUE : NON_MEMBER_VALUE, AUTH_TTL);
        LOG.debug(
                "Meeting membership cache miss: user={} meeting={} tenant={} result={}",
                userId, meetingId, tenantId, member);
        return member;
    }

    private boolean lookupMembership(final Long tenantId, final UUID meetingId, final Long userId) {
        Meeting meeting = meetingRepository.findById(meetingId).orElse(null);
        if (meeting == null || !meeting.getTenantId().equals(tenantId)) {
            return false;
        }
        if (meeting.getCreatedBy().equals(userId)) {
            return true;
        }
        return meeting.getTeamId() != null
                && teamMemberRepository.findByTeamIdAndUserId(meeting.getTeamId(), userId).isPresent();
    }
}
