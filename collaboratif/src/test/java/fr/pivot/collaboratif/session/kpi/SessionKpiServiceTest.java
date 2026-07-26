package fr.pivot.collaboratif.session.kpi;

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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionKpiServiceTest {

    private static final Long TENANT_ID = 1L;
    private static final Long TEAM_ID = 42L;

    @Mock
    private SessionKpiRepository kpiRepository;

    private SessionKpiService kpiService;
    private CollaboratifRequestPrincipal userPrincipal;
    private CollaboratifRequestPrincipal guestPrincipal;

    @BeforeEach
    void setUp() {
        kpiService = new SessionKpiService(kpiRepository);
        userPrincipal = new CollaboratifRequestPrincipal(10L, TENANT_ID, "ROLE_USER");
        guestPrincipal = new CollaboratifRequestPrincipal(10L, TENANT_ID, "ROLE_GUEST");
    }

    private static SessionKpiAggregate aggregate(
            final long sessionsRun, final long activitiesRun, final double avgParticipants,
            final double participationRate, final double completionRate) {
        return new SessionKpiAggregate() {
            @Override
            public long getSessionsRun() {
                return sessionsRun;
            }

            @Override
            public long getActivitiesRun() {
                return activitiesRun;
            }

            @Override
            public double getAvgParticipants() {
                return avgParticipants;
            }

            @Override
            public double getParticipationRate() {
                return participationRate;
            }

            @Override
            public double getCompletionRate() {
                return completionRate;
            }
        };
    }

    @Test
    void listDefinitions_forAuthorizedRole_returnsAllFive() {
        List<KpiDefinitionResponse> definitions = kpiService.listDefinitions(userPrincipal);

        assertThat(definitions).hasSize(5);
        assertThat(definitions).extracting(KpiDefinitionResponse::kpiKey).containsExactlyInAnyOrder(
                "session.sessions_run", "session.avg_participants", "session.participation_rate",
                "session.activities_run", "session.completion_rate");
    }

    @Test
    void listDefinitions_forUnauthorizedRole_returnsNone() {
        List<KpiDefinitionResponse> definitions = kpiService.listDefinitions(guestPrincipal);

        assertThat(definitions).isEmpty();
    }

    @Test
    void resolve_unknownKpiKey_throwsKpiNotFound() {
        assertThatThrownBy(() -> kpiService.resolve("session.not_a_kpi", "tenant", null, userPrincipal))
                .isInstanceOf(KpiNotFoundException.class);
    }

    @Test
    void resolve_forUnauthorizedRole_throwsSessionForbidden() {
        assertThatThrownBy(() -> kpiService.resolve("session.sessions_run", "tenant", null, guestPrincipal))
                .isInstanceOf(SessionForbiddenException.class)
                .satisfies(ex -> assertThat(((SessionForbiddenException) ex).getCode()).isEqualTo("KPI_ACCESS_DENIED"));
    }

    @Test
    void resolve_unsupportedScope_throwsSessionValidation() {
        assertThatThrownBy(() -> kpiService.resolve("session.avg_participants", "tenant", null, userPrincipal))
                .isInstanceOf(SessionValidationException.class)
                .satisfies(ex ->
                        assertThat(((SessionValidationException) ex).getCode()).isEqualTo("UNSUPPORTED_KPI_SCOPE"));
    }

    @Test
    void resolve_teamScopeWithoutTeamId_throwsSessionValidation() {
        assertThatThrownBy(() -> kpiService.resolve("session.avg_participants", "team", null, userPrincipal))
                .isInstanceOf(SessionValidationException.class);
    }

    @Test
    void resolve_teamScopeForAnotherTenantsTeam_throwsKpiNotFound() {
        when(kpiRepository.teamBelongsToTenant(TEAM_ID, TENANT_ID)).thenReturn(false);

        assertThatThrownBy(() -> kpiService.resolve("session.avg_participants", "team", TEAM_ID, userPrincipal))
                .isInstanceOf(KpiNotFoundException.class);
    }

    @Test
    void resolve_teamScopeForUnknownTeam_throwsKpiNotFound() {
        when(kpiRepository.teamBelongsToTenant(TEAM_ID, TENANT_ID)).thenReturn(false);

        assertThatThrownBy(() -> kpiService.resolve("session.avg_participants", "team", TEAM_ID, userPrincipal))
                .isInstanceOf(KpiNotFoundException.class);
    }

    @Test
    void resolve_tenantScope_returnsTheAggregateSessionsRun() {
        when(kpiRepository.aggregate(eq(TENANT_ID), isNull())).thenReturn(aggregate(7, 7, 0, 0, 0));

        KpiRefResponse response = kpiService.resolve("session.sessions_run", "tenant", null, userPrincipal);

        assertThat(response.value()).isEqualTo(7.0);
        assertThat(response.source()).isEqualTo("collaboratif");
        assertThat(response.kpiKey()).isEqualTo("session.sessions_run");
        assertThat(response.unit()).isEqualTo("count");
        assertThat(response.tenantId()).isEqualTo(TENANT_ID);
        assertThat(response.scope()).isEmpty();
        assertThat(response.observedAt()).isNotNull();
    }

    @Test
    void resolve_teamScope_returnsTheAggregateCompletionRateAndTeamScope() {
        when(kpiRepository.teamBelongsToTenant(TEAM_ID, TENANT_ID)).thenReturn(true);
        when(kpiRepository.aggregate(TENANT_ID, TEAM_ID)).thenReturn(aggregate(4, 4, 3.5, 80.0, 75.0));

        KpiRefResponse response = kpiService.resolve("session.completion_rate", "team", TEAM_ID, userPrincipal);

        assertThat(response.value()).isEqualTo(75.0);
        assertThat(response.unit()).isEqualTo("%");
        assertThat(response.scope()).containsEntry("teamId", TEAM_ID);
    }

    @Test
    void resolve_neverCallsAggregateBeforeAuthorizationAndScopeChecksPass() {
        assertThatThrownBy(() -> kpiService.resolve("session.not_a_kpi", "tenant", null, userPrincipal))
                .isInstanceOf(KpiNotFoundException.class);

        org.mockito.Mockito.verifyNoInteractions(kpiRepository);
    }
}
