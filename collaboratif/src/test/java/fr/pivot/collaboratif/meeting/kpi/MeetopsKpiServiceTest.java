package fr.pivot.collaboratif.meeting.kpi;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.exception.KpiNotFoundException;
import fr.pivot.collaboratif.exception.SessionForbiddenException;
import fr.pivot.collaboratif.exception.SessionValidationException;
import fr.pivot.collaboratif.session.kpi.dto.KpiDefinitionResponse;
import fr.pivot.collaboratif.session.kpi.dto.KpiRefResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MeetopsKpiService} (EN12.3), mirroring {@code
 * fr.pivot.collaboratif.session.kpi.SessionKpiServiceTest}'s own coverage shape for EN19.4.
 * Numeric correctness of the underlying aggregate query itself is covered by {@link
 * MeetopsKpiRepositoryIT} — this class only exercises service-layer logic (role/scope
 * validation, error mapping, dispatch to the mocked repository) with a mocked repository.
 */
@ExtendWith(MockitoExtension.class)
class MeetopsKpiServiceTest {

    private static final Long TENANT_ID = 1L;
    private static final Long TEAM_ID = 42L;

    @Mock
    private MeetopsKpiRepository kpiRepository;

    private MeetopsKpiService kpiService;
    private CollaboratifRequestPrincipal userPrincipal;
    private CollaboratifRequestPrincipal guestPrincipal;

    @BeforeEach
    void setUp() {
        kpiService = new MeetopsKpiService(kpiRepository);
        userPrincipal = new CollaboratifRequestPrincipal(10L, TENANT_ID, "ROLE_USER");
        guestPrincipal = new CollaboratifRequestPrincipal(10L, TENANT_ID, "ROLE_GUEST");
    }

    private static MeetopsKpiAggregate aggregate(
            final long meetingsRun, final double participationRate, final double actionCompletionRate,
            final double agendaAdherence, final double minutesSharedRate) {
        return new MeetopsKpiAggregate() {
            @Override
            public long getMeetingsRun() {
                return meetingsRun;
            }

            @Override
            public double getParticipationRate() {
                return participationRate;
            }

            @Override
            public double getActionCompletionRate() {
                return actionCompletionRate;
            }

            @Override
            public double getAgendaAdherence() {
                return agendaAdherence;
            }

            @Override
            public double getMinutesSharedRate() {
                return minutesSharedRate;
            }
        };
    }

    @Test
    void listDefinitions_forAuthorizedRole_returnsAllFive() {
        List<KpiDefinitionResponse> definitions = kpiService.listDefinitions(userPrincipal);

        assertThat(definitions).hasSize(5);
        assertThat(definitions).extracting(KpiDefinitionResponse::kpiKey).containsExactlyInAnyOrder(
                "meetops.meetings_run", "meetops.participation_rate", "meetops.action_completion_rate",
                "meetops.agenda_adherence", "meetops.minutes_shared_rate");
        assertThat(definitions).allSatisfy(def -> assertThat(def.source()).isEqualTo("collaboratif"));
    }

    @Test
    void listDefinitions_forUnauthorizedRole_returnsNone() {
        List<KpiDefinitionResponse> definitions = kpiService.listDefinitions(guestPrincipal);

        assertThat(definitions).isEmpty();
    }

    @Test
    void supports_forOwnKeys_returnsTrue() {
        assertThat(kpiService.supports("meetops.meetings_run")).isTrue();
        assertThat(kpiService.supports("meetops.participation_rate")).isTrue();
    }

    @Test
    void supports_forUnknownOrForeignDomainKey_returnsFalse() {
        assertThat(kpiService.supports("meetops.not_a_kpi")).isFalse();
        assertThat(kpiService.supports("session.sessions_run")).isFalse();
    }

    @Test
    void resolve_unknownKpiKey_throwsKpiNotFound() {
        assertThatThrownBy(() -> kpiService.resolve("meetops.not_a_kpi", "tenant", null, userPrincipal))
                .isInstanceOf(KpiNotFoundException.class);
    }

    @Test
    void resolve_forUnauthorizedRole_throwsSessionForbidden() {
        assertThatThrownBy(() -> kpiService.resolve("meetops.meetings_run", "tenant", null, guestPrincipal))
                .isInstanceOf(SessionForbiddenException.class)
                .satisfies(ex -> assertThat(((SessionForbiddenException) ex).getCode()).isEqualTo("KPI_ACCESS_DENIED"));
    }

    @Test
    void resolve_unsupportedScope_throwsSessionValidation() {
        assertThatThrownBy(() -> kpiService.resolve("meetops.participation_rate", "tenant", null, userPrincipal))
                .isInstanceOf(SessionValidationException.class)
                .satisfies(ex ->
                        assertThat(((SessionValidationException) ex).getCode()).isEqualTo("UNSUPPORTED_KPI_SCOPE"));
    }

    @Test
    void resolve_teamScopeWithoutTeamId_throwsSessionValidation() {
        assertThatThrownBy(() -> kpiService.resolve("meetops.participation_rate", "team", null, userPrincipal))
                .isInstanceOf(SessionValidationException.class);
    }

    @Test
    void resolve_teamScopeForAnotherTenantsTeam_throwsKpiNotFound() {
        when(kpiRepository.teamBelongsToTenant(TEAM_ID, TENANT_ID)).thenReturn(false);

        assertThatThrownBy(() -> kpiService.resolve("meetops.participation_rate", "team", TEAM_ID, userPrincipal))
                .isInstanceOf(KpiNotFoundException.class);
    }

    @Test
    void resolve_tenantScope_returnsTheAggregateMeetingsRun() {
        when(kpiRepository.aggregate(eq(TENANT_ID), isNull())).thenReturn(aggregate(7, 0, 0, 0, 0));

        KpiRefResponse response = kpiService.resolve("meetops.meetings_run", "tenant", null, userPrincipal);

        assertThat(response.value()).isEqualTo(7.0);
        assertThat(response.source()).isEqualTo("collaboratif");
        assertThat(response.kpiKey()).isEqualTo("meetops.meetings_run");
        assertThat(response.unit()).isEqualTo("count");
        assertThat(response.tenantId()).isEqualTo(TENANT_ID);
        assertThat(response.scope()).isEmpty();
        assertThat(response.observedAt()).isNotNull();
    }

    @Test
    void resolve_teamScope_returnsTheAggregateAgendaAdherenceAndTeamScope() {
        when(kpiRepository.teamBelongsToTenant(TEAM_ID, TENANT_ID)).thenReturn(true);
        when(kpiRepository.aggregate(TENANT_ID, TEAM_ID)).thenReturn(aggregate(4, 75.0, 33.3, 87.5, 50.0));

        KpiRefResponse response = kpiService.resolve("meetops.agenda_adherence", "team", TEAM_ID, userPrincipal);

        assertThat(response.value()).isEqualTo(87.5);
        assertThat(response.unit()).isEqualTo("%");
        assertThat(response.scope()).containsEntry("teamId", TEAM_ID);
    }

    @Test
    void resolve_teamScope_returnsEachOfTheFourTeamOnlyKpiFields() {
        when(kpiRepository.teamBelongsToTenant(TEAM_ID, TENANT_ID)).thenReturn(true);
        when(kpiRepository.aggregate(TENANT_ID, TEAM_ID)).thenReturn(aggregate(4, 75.0, 33.3, 87.5, 50.0));

        assertThat(kpiService.resolve("meetops.participation_rate", "team", TEAM_ID, userPrincipal).value())
                .isEqualTo(75.0);
        assertThat(kpiService.resolve("meetops.action_completion_rate", "team", TEAM_ID, userPrincipal).value())
                .isEqualTo(33.3);
        assertThat(kpiService.resolve("meetops.minutes_shared_rate", "team", TEAM_ID, userPrincipal).value())
                .isEqualTo(50.0);
    }

    @Test
    void resolve_neverCallsAggregateBeforeAuthorizationAndScopeChecksPass() {
        assertThatThrownBy(() -> kpiService.resolve("meetops.not_a_kpi", "tenant", null, userPrincipal))
                .isInstanceOf(KpiNotFoundException.class);

        verifyNoInteractions(kpiRepository);
    }
}
