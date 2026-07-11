package fr.pivot.core.team;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour l'entité {@link TeamMember}.
 *
 * <p>Traçabilité EN17.1 (volet team, pivot-core#171) — critère « Entité TeamMember en BDD
 * (team_id, user_id, created_at) » ; champs {@code role}/{@code updated_at} anticipés ADR-027
 * (pivot-docs#227).
 */
class TeamMemberTest {

    @Test
    void constructor_shouldSetTeamIdAndUserId() {
        final TeamMember member = new TeamMember(10L, 42L);

        assertThat(member.getId()).isNull();
        assertThat(member.getTeamId()).isEqualTo(10L);
        assertThat(member.getUserId()).isEqualTo(42L);
        assertThat(member.getCreatedAt()).isNotNull();
    }

    @Test
    void constructor_shouldDefaultRoleToMembre() {
        final TeamMember member = new TeamMember(10L, 42L);

        assertThat(member.getRole()).isEqualTo(TeamMember.ROLE_MEMBRE);
        assertThat(member.getUpdatedAt()).isNotNull();
    }

    @Test
    void setRole_shouldStoreRole() {
        final TeamMember member = new TeamMember(10L, 42L);

        member.setRole(TeamMember.ROLE_RESPONSABLE);

        assertThat(member.getRole()).isEqualTo("RESPONSABLE");
    }

    @Test
    void onUpdate_shouldRefreshUpdatedAt() {
        final TeamMember member = new TeamMember(10L, 42L);
        final Instant before = member.getUpdatedAt();

        member.onUpdate();

        assertThat(member.getUpdatedAt()).isAfterOrEqualTo(before);
        assertThat(member.getCreatedAt()).isBeforeOrEqualTo(member.getUpdatedAt());
    }
}
