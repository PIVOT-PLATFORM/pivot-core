package fr.pivot.collaboratif.meeting.kpi;

import fr.pivot.collaboratif.AbstractCollaboratifIntegrationTest;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Integration tests for {@link MeetopsKpiRepository#aggregate} (EN12.3) against a real PostgreSQL
 * via Testcontainers ({@link AbstractCollaboratifIntegrationTest}'s module-wide singleton
 * container) — proves the arithmetic of all five MeetOps KPIs, which {@link MeetopsKpiServiceTest}
 * cannot (it mocks the repository).
 *
 * <p>Seeds {@code collaboratif.meetings}/{@code agenda_items}/{@code meeting_actions}/{@code
 * meeting_decisions}/{@code meeting_report} directly via raw JDBC (bypassing {@code
 * MeetingAnimationService}'s state machine entirely) so every input to the aggregate query is
 * pinned to an exact, hand-computable value — the same "seed the schema directly, not through the
 * application layer" approach {@link fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport}
 * already uses for the {@code public} schema.
 *
 * <p><strong>The fixture, and its hand-computed expectations</strong> (team {@code TG}, 2
 * members: {@code userA}, {@code userB}):
 * <ul>
 *   <li>{@code M1} ({@code ENDED}, team {@code TG}): agenda items 600s-actual/600s-planned (100%
 *       adherence) and 1500s-actual/1200s-planned (75%); actions {@code OPEN} (owner
 *       {@code userA}) and {@code DONE} (no owner); decision by {@code userB}; a frozen report.
 *   <li>{@code M2} ({@code ENDED}, team {@code TG}): one agenda item 300s-actual/600s-planned
 *       (50%); one {@code OPEN} action (owner {@code userA}); no decision; no report.
 *   <li>{@code M3} ({@code IN_PROGRESS}, team {@code TG}): counts toward {@code meetings_run} only
 *       (not {@code ENDED}, so excluded from every other KPI).
 *   <li>{@code M4} ({@code ENDED}, no team — personal): only relevant to the tenant-scope
 *       {@code meetings_run} test.
 * </ul>
 * Expected team-scope values: {@code meetingsRun=3} ({@code M1}+{@code M2}+{@code M3}),
 * {@code agendaAdherence=75.0} (avg of 100/75/50), {@code actionCompletionRate≈33.33} (1 done out
 * of 3 actions), {@code minutesSharedRate=50.0} (1 reported out of 2 ended), {@code
 * participationRate=75.0} (engaged member-meetings 2+1=3, out of team-size(2)×ended-meetings(2)=4).
 * Expected tenant-scope {@code meetingsRun=4} ({@code M1}+{@code M2}+{@code M3}+{@code M4}).
 */
@SpringBootTest
class MeetopsKpiRepositoryIT extends AbstractCollaboratifIntegrationTest {

    @Autowired
    private MeetopsKpiRepository kpiRepository;

    private long tenantId;
    private long teamId;
    private long userA;
    private long userB;

    @BeforeEach
    void setUp() throws SQLException {
        AuthFixture owner = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantId = owner.tenantId();
        userA = owner.userId();
        userB = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
        teamId = PlatformAuthTestSupport.seedTeam(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, "Team G");
        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), teamId, userA);
        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), teamId, userB);
    }

    // -------------------------------------------------------------------------
    // JDBC seeding helpers — direct inserts into collaboratif.*, bypassing the app layer
    // -------------------------------------------------------------------------

    private UUID seedMeeting(final Long teamIdOrNull, final String status) throws SQLException {
        UUID id = UUID.randomUUID();
        String sql = "INSERT INTO collaboratif.meetings "
                + "(id, tenant_id, team_id, title, scheduled_at, total_duration_minutes, status, created_by) "
                + "VALUES (?, ?, ?, 'Meeting', now(), 30, ?, ?)";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setLong(2, tenantId);
            if (teamIdOrNull != null) {
                ps.setLong(3, teamIdOrNull);
            } else {
                ps.setNull(3, Types.BIGINT);
            }
            ps.setString(4, status);
            ps.setLong(5, userA);
            ps.executeUpdate();
        }
        return id;
    }

    private void seedAgendaItem(
            final UUID meetingId, final int position, final int durationMinutes, final int actualSeconds)
            throws SQLException {
        String sql = "INSERT INTO collaboratif.agenda_items "
                + "(id, meeting_id, position, title, duration_minutes, type, item_status, actual_seconds) "
                + "VALUES (?, ?, ?, 'Item', ?, 'INFO', 'DONE', ?)";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, meetingId);
            ps.setInt(3, position);
            ps.setInt(4, durationMinutes);
            ps.setInt(5, actualSeconds);
            ps.executeUpdate();
        }
    }

    private void seedAction(final UUID meetingId, final Long ownerUserIdOrNull, final String status)
            throws SQLException {
        String sql = "INSERT INTO collaboratif.meeting_actions "
                + "(id, tenant_id, meeting_id, label, owner_user_id, status) VALUES (?, ?, ?, 'Action', ?, ?)";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setLong(2, tenantId);
            ps.setObject(3, meetingId);
            if (ownerUserIdOrNull != null) {
                ps.setLong(4, ownerUserIdOrNull);
            } else {
                ps.setNull(4, Types.BIGINT);
            }
            ps.setString(5, status);
            ps.executeUpdate();
        }
    }

    private void seedDecision(final UUID meetingId, final long createdBy) throws SQLException {
        String sql = "INSERT INTO collaboratif.meeting_decisions "
                + "(id, tenant_id, meeting_id, label, created_by) VALUES (?, ?, ?, 'Decision', ?)";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setLong(2, tenantId);
            ps.setObject(3, meetingId);
            ps.setLong(4, createdBy);
            ps.executeUpdate();
        }
    }

    private void seedReport(final UUID meetingId) throws SQLException {
        String sql = "INSERT INTO collaboratif.meeting_report "
                + "(meeting_id, tenant_id, content, generated_by) VALUES (?, ?, '{}'::jsonb, ?)";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, meetingId);
            ps.setLong(2, tenantId);
            ps.setLong(3, userA);
            ps.executeUpdate();
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /** Seeds the full fixture documented in this class's own Javadoc. */
    private void seedFullFixture() throws SQLException {
        UUID m1 = seedMeeting(teamId, "ENDED");
        seedAgendaItem(m1, 0, 10, 600);
        seedAgendaItem(m1, 1, 20, 1500);
        seedAction(m1, userA, "OPEN");
        seedAction(m1, null, "DONE");
        seedDecision(m1, userB);
        seedReport(m1);

        UUID m2 = seedMeeting(teamId, "ENDED");
        seedAgendaItem(m2, 0, 10, 300);
        seedAction(m2, userA, "OPEN");

        seedMeeting(teamId, "IN_PROGRESS");
        seedMeeting(null, "ENDED");
    }

    // -------------------------------------------------------------------------
    // meetings_run
    // -------------------------------------------------------------------------

    @Test
    void aggregate_teamScope_meetingsRun_countsOnlyStartedMeetingsOfThatTeam() throws SQLException {
        seedFullFixture();

        MeetopsKpiAggregate aggregate = kpiRepository.aggregate(tenantId, teamId);

        assertThat(aggregate.getMeetingsRun()).isEqualTo(3);
    }

    @Test
    void aggregate_tenantScope_meetingsRun_includesTeamAndPersonalMeetings() throws SQLException {
        seedFullFixture();

        MeetopsKpiAggregate aggregate = kpiRepository.aggregate(tenantId, null);

        assertThat(aggregate.getMeetingsRun()).isEqualTo(4);
    }

    @Test
    void aggregate_draftAndConfirmedMeetings_neverCountTowardMeetingsRun() throws SQLException {
        seedMeeting(teamId, "DRAFT");
        seedMeeting(teamId, "CONFIRMED");

        MeetopsKpiAggregate aggregate = kpiRepository.aggregate(tenantId, teamId);

        assertThat(aggregate.getMeetingsRun()).isZero();
    }

    // -------------------------------------------------------------------------
    // agenda_adherence
    // -------------------------------------------------------------------------

    @Test
    void aggregate_agendaAdherence_averagesBoundedPerItemAdherenceAcrossEndedMeetings() throws SQLException {
        seedFullFixture();

        MeetopsKpiAggregate aggregate = kpiRepository.aggregate(tenantId, teamId);

        // (100 + 75 + 50) / 3 = 75.0 — see this class's Javadoc for the per-item derivation.
        assertThat(aggregate.getAgendaAdherence()).isCloseTo(75.0, within(0.001));
    }

    @Test
    void aggregate_agendaAdherence_neverGoesNegativeForAWildOverrun() throws SQLException {
        UUID meeting = seedMeeting(teamId, "ENDED");
        seedAgendaItem(meeting, 0, 10, 6000); // 10x the planned 600s

        MeetopsKpiAggregate aggregate = kpiRepository.aggregate(tenantId, teamId);

        assertThat(aggregate.getAgendaAdherence()).isEqualTo(0.0);
    }

    @Test
    void aggregate_agendaAdherence_withNoDoneItem_isZero() throws SQLException {
        seedMeeting(teamId, "ENDED");

        MeetopsKpiAggregate aggregate = kpiRepository.aggregate(tenantId, teamId);

        assertThat(aggregate.getAgendaAdherence()).isZero();
    }

    // -------------------------------------------------------------------------
    // action_completion_rate
    // -------------------------------------------------------------------------

    @Test
    void aggregate_actionCompletionRate_countsAnyNonOpenStatusAsDone() throws SQLException {
        seedFullFixture();

        MeetopsKpiAggregate aggregate = kpiRepository.aggregate(tenantId, teamId);

        // 1 DONE out of 3 actions (M1: OPEN+DONE, M2: OPEN) = 33.33...%
        assertThat(aggregate.getActionCompletionRate()).isCloseTo(33.333333, within(0.001));
    }

    @Test
    void aggregate_actionCompletionRate_withNoAction_isZero() throws SQLException {
        seedMeeting(teamId, "ENDED");

        MeetopsKpiAggregate aggregate = kpiRepository.aggregate(tenantId, teamId);

        assertThat(aggregate.getActionCompletionRate()).isZero();
    }

    // -------------------------------------------------------------------------
    // minutes_shared_rate
    // -------------------------------------------------------------------------

    @Test
    void aggregate_minutesSharedRate_isShareOfEndedMeetingsWithAFrozenReport() throws SQLException {
        seedFullFixture();

        MeetopsKpiAggregate aggregate = kpiRepository.aggregate(tenantId, teamId);

        // 1 reported (M1) out of 2 ended (M1, M2) = 50%
        assertThat(aggregate.getMinutesSharedRate()).isCloseTo(50.0, within(0.001));
    }

    @Test
    void aggregate_minutesSharedRate_withNoEndedMeeting_isZero() throws SQLException {
        seedMeeting(teamId, "IN_PROGRESS");

        MeetopsKpiAggregate aggregate = kpiRepository.aggregate(tenantId, teamId);

        assertThat(aggregate.getMinutesSharedRate()).isZero();
    }

    // -------------------------------------------------------------------------
    // participation_rate
    // -------------------------------------------------------------------------

    @Test
    void aggregate_participationRate_isShareOfTeamMembersEngagedAcrossEndedMeetings() throws SQLException {
        seedFullFixture();

        MeetopsKpiAggregate aggregate = kpiRepository.aggregate(tenantId, teamId);

        // engaged member-meetings: M1={userA,userB}=2, M2={userA}=1, sum=3;
        // denom = team size(2) * ended meetings(2) = 4 => 3/4*100 = 75.0
        assertThat(aggregate.getParticipationRate()).isCloseTo(75.0, within(0.001));
    }

    @Test
    void aggregate_participationRate_ignoresAnActionOwnerOutsideTheTeam() throws SQLException {
        long outsider = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
        UUID meeting = seedMeeting(teamId, "ENDED");
        seedAction(meeting, outsider, "OPEN");

        MeetopsKpiAggregate aggregate = kpiRepository.aggregate(tenantId, teamId);

        assertThat(aggregate.getParticipationRate()).isZero();
    }

    @Test
    void aggregate_participationRate_withNoTeamMember_isZero() throws SQLException {
        long lonelyTeam = PlatformAuthTestSupport.seedTeam(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, "Lonely Team");
        UUID meeting = seedMeeting(lonelyTeam, "ENDED");
        seedAction(meeting, userA, "OPEN");

        MeetopsKpiAggregate aggregate = kpiRepository.aggregate(tenantId, lonelyTeam);

        assertThat(aggregate.getParticipationRate()).isZero();
    }

    // -------------------------------------------------------------------------
    // Tenant isolation
    // -------------------------------------------------------------------------

    @Test
    void aggregate_neverCountsAnotherTenantsMeetings() throws SQLException {
        seedFullFixture();
        long otherTenantId = PlatformAuthTestSupport.seedTenant(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), null);

        MeetopsKpiAggregate aggregate = kpiRepository.aggregate(otherTenantId, null);

        assertThat(aggregate.getMeetingsRun()).isZero();
    }

    // -------------------------------------------------------------------------
    // teamBelongsToTenant
    // -------------------------------------------------------------------------

    @Test
    void teamBelongsToTenant_forOwnTeam_returnsTrue() {
        assertThat(kpiRepository.teamBelongsToTenant(teamId, tenantId)).isTrue();
    }

    @Test
    void teamBelongsToTenant_forAnotherTenantsTeam_returnsFalse() throws SQLException {
        long otherTenantId = PlatformAuthTestSupport.seedTenant(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), null);

        assertThat(kpiRepository.teamBelongsToTenant(teamId, otherTenantId)).isFalse();
    }

    @Test
    void teamBelongsToTenant_forUnknownTeam_returnsFalse() {
        assertThat(kpiRepository.teamBelongsToTenant(999_999L, tenantId)).isFalse();
    }
}
