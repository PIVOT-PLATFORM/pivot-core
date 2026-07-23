package fr.pivot.collaboratif.session;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.exception.SessionNotFoundException;
import fr.pivot.core.team.TeamMember;
import fr.pivot.core.team.TeamMemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionAccessServiceTest {

    private static final Long TENANT_ID = 1L;
    private static final Long OTHER_TENANT_ID = 2L;
    private static final Long CREATOR_ID = 10L;
    private static final Long OTHER_USER_ID = 20L;
    private static final Long TEAM_ID = 100L;

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private TeamMemberRepository teamMemberRepository;

    private SessionAccessService accessService() {
        return new SessionAccessService(sessionRepository, teamMemberRepository);
    }

    private Session session(final Long teamId) {
        return new Session(TENANT_ID, teamId, "Title", SessionType.POLL, "ABCDEF", "{}", CREATOR_ID, Instant.now());
    }

    @Test
    void resolveSessionForCallerReturnsTheSessionForItsCreator() {
        Session session = session(null);
        UUID id = session.getId();
        when(sessionRepository.findById(id)).thenReturn(Optional.of(session));

        Session resolved = accessService().resolveSessionForCaller(
                id, new CollaboratifRequestPrincipal(CREATOR_ID, TENANT_ID, "ROLE_USER"));

        assertThat(resolved).isSameAs(session);
    }

    @Test
    void resolveSessionForCallerReturnsTheSessionForATeamMember() {
        Session session = session(TEAM_ID);
        UUID id = session.getId();
        when(sessionRepository.findById(id)).thenReturn(Optional.of(session));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, OTHER_USER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, OTHER_USER_ID)));

        Session resolved = accessService().resolveSessionForCaller(
                id, new CollaboratifRequestPrincipal(OTHER_USER_ID, TENANT_ID, "ROLE_USER"));

        assertThat(resolved).isSameAs(session);
    }

    @Test
    void resolveSessionForCallerThrowsNotFoundForANonMemberNonCreator() {
        Session session = session(TEAM_ID);
        UUID id = session.getId();
        when(sessionRepository.findById(id)).thenReturn(Optional.of(session));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, OTHER_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accessService().resolveSessionForCaller(
                id, new CollaboratifRequestPrincipal(OTHER_USER_ID, TENANT_ID, "ROLE_USER")))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void resolveSessionForCallerThrowsNotFoundForAnUnknownSession() {
        UUID id = UUID.randomUUID();
        when(sessionRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accessService().resolveSessionForCaller(
                id, new CollaboratifRequestPrincipal(CREATOR_ID, TENANT_ID, "ROLE_USER")))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void resolveSessionForCallerThrowsNotFoundAcrossTenants() {
        Session session = session(null);
        UUID id = session.getId();
        when(sessionRepository.findById(id)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> accessService().resolveSessionForCaller(
                id, new CollaboratifRequestPrincipal(CREATOR_ID, OTHER_TENANT_ID, "ROLE_USER")))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void resolveSessionForOwnerOrAdminAllowsTheCreator() {
        Session session = session(null);
        UUID id = session.getId();
        when(sessionRepository.findById(id)).thenReturn(Optional.of(session));

        Session resolved = accessService().resolveSessionForOwnerOrAdmin(
                id, new CollaboratifRequestPrincipal(CREATOR_ID, TENANT_ID, "ROLE_USER"));

        assertThat(resolved).isSameAs(session);
    }

    @Test
    void resolveSessionForOwnerOrAdminAllowsARoleAdmin() {
        Session session = session(null);
        UUID id = session.getId();
        when(sessionRepository.findById(id)).thenReturn(Optional.of(session));

        Session resolved = accessService().resolveSessionForOwnerOrAdmin(
                id, new CollaboratifRequestPrincipal(OTHER_USER_ID, TENANT_ID, "ROLE_ADMIN"));

        assertThat(resolved).isSameAs(session);
    }

    @Test
    void resolveSessionForOwnerOrAdminRejectsATeamMemberWhoIsNeitherOwnerNorAdmin() {
        Session session = session(TEAM_ID);
        UUID id = session.getId();
        when(sessionRepository.findById(id)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> accessService().resolveSessionForOwnerOrAdmin(
                id, new CollaboratifRequestPrincipal(OTHER_USER_ID, TENANT_ID, "ROLE_USER")))
                .isInstanceOf(SessionNotFoundException.class);
    }
}
