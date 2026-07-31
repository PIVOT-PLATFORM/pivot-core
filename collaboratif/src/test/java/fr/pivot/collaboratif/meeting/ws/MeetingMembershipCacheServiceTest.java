package fr.pivot.collaboratif.meeting.ws;

import fr.pivot.collaboratif.meeting.Meeting;
import fr.pivot.collaboratif.meeting.MeetingRepository;
import fr.pivot.core.team.TeamMember;
import fr.pivot.core.team.TeamMemberRepository;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MeetingMembershipCacheService} (US12.2.1 AC-S3) — mirrors {@code
 * fr.pivot.collaboratif.session.ws.SessionMembershipCacheServiceTest}'s coverage shape: cache
 * hit/miss paths, cross-tenant isolation (AC-S1's guarantee re-verified at the WS layer), owner
 * membership, and team membership.
 */
@ExtendWith(MockitoExtension.class)
class MeetingMembershipCacheServiceTest {

    private static final Long TENANT_ID = 100L;
    private static final UUID MEETING_ID = UUID.randomUUID();
    private static final Long USER_ID = 42L;
    private static final Long TEAM_ID = 55L;
    private static final String CACHE_KEY = "ws:meeting-auth:" + TENANT_ID + ":" + MEETING_ID + ":" + USER_ID;

    @Mock
    private MeetingRepository meetingRepository;
    @Mock
    private TeamMemberRepository teamMemberRepository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private MeetingMembershipCacheService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new MeetingMembershipCacheService(meetingRepository, teamMemberRepository, redisTemplate);
    }

    private Meeting meeting(final Long tenantId, final Long teamId, final Long createdBy) {
        return new Meeting(tenantId, teamId, "Title", Instant.now(), 30, createdBy, Instant.now());
    }

    @Test
    void isMemberReturnsTrueOnACachedMemberHitWithoutHittingTheDatabase() {
        when(valueOperations.get(CACHE_KEY)).thenReturn("1");

        boolean member = service.isMember(TENANT_ID, MEETING_ID, USER_ID);

        assertThat(member).isTrue();
        verify(meetingRepository, never()).findById(any());
    }

    @Test
    void isMemberReturnsFalseOnACachedNonMemberHitWithoutHittingTheDatabase() {
        when(valueOperations.get(CACHE_KEY)).thenReturn("0");

        boolean member = service.isMember(TENANT_ID, MEETING_ID, USER_ID);

        assertThat(member).isFalse();
        verify(meetingRepository, never()).findById(any());
    }

    @Test
    void isMemberOnACacheMissReturnsTrueAndCachesItWhenTheUserIsTheOwner() {
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        Meeting meeting = meeting(TENANT_ID, null, USER_ID);
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting));

        boolean member = service.isMember(TENANT_ID, MEETING_ID, USER_ID);

        assertThat(member).isTrue();
        verify(valueOperations).set(eq(CACHE_KEY), eq("1"), eq(Duration.ofSeconds(5)));
        verify(teamMemberRepository, never()).findByTeamIdAndUserId(any(), any());
    }

    @Test
    void isMemberOnACacheMissReturnsTrueAndCachesItWhenTheUserIsATeamMember() {
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        Meeting meeting = meeting(TENANT_ID, TEAM_ID, 999L);
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, USER_ID))
                .thenReturn(Optional.of(mock(TeamMember.class)));

        boolean member = service.isMember(TENANT_ID, MEETING_ID, USER_ID);

        assertThat(member).isTrue();
        verify(valueOperations).set(eq(CACHE_KEY), eq("1"), eq(Duration.ofSeconds(5)));
    }

    @Test
    void isMemberOnACacheMissReturnsFalseAndCachesItWhenTheMeetingDoesNotExist() {
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.empty());

        boolean member = service.isMember(TENANT_ID, MEETING_ID, USER_ID);

        assertThat(member).isFalse();
        verify(valueOperations).set(eq(CACHE_KEY), eq("0"), eq(Duration.ofSeconds(5)));
    }

    /**
     * A meetingId belonging to a <em>different</em> tenant than the requesting user must be
     * treated as non-existent — the WS-layer re-verification of AC-S1's cross-tenant guarantee.
     * The team-membership repository must never even be consulted in this case.
     */
    @Test
    void isMemberReturnsFalseWhenTheMeetingBelongsToADifferentTenant() {
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        Meeting foreignMeeting = meeting(999L, TEAM_ID, USER_ID);
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(foreignMeeting));

        boolean member = service.isMember(TENANT_ID, MEETING_ID, USER_ID);

        assertThat(member).isFalse();
        verify(teamMemberRepository, never()).findByTeamIdAndUserId(any(), any());
        verify(valueOperations).set(eq(CACHE_KEY), eq("0"), eq(Duration.ofSeconds(5)));
    }

    @Test
    void isMemberOnACacheMissReturnsFalseWhenNeitherOwnerNorTeamMember() {
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        Meeting meeting = meeting(TENANT_ID, TEAM_ID, 999L);
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, USER_ID)).thenReturn(Optional.empty());

        boolean member = service.isMember(TENANT_ID, MEETING_ID, USER_ID);

        assertThat(member).isFalse();
        verify(valueOperations).set(eq(CACHE_KEY), eq("0"), eq(Duration.ofSeconds(5)));
    }

    @Test
    void isMemberOnACacheMissReturnsFalseWhenNoTeamAndNotOwner() {
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        Meeting meeting = meeting(TENANT_ID, null, 999L);
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting));

        boolean member = service.isMember(TENANT_ID, MEETING_ID, USER_ID);

        assertThat(member).isFalse();
        verify(teamMemberRepository, never()).findByTeamIdAndUserId(any(), any());
    }
}
