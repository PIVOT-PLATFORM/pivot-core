package fr.pivot.core.team;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour l'entité {@link TeamMember}.
 *
 * <p>Traçabilité EN17.1 (volet team, pivot-core#171) — critère « Entité TeamMember en BDD
 * (team_id, user_id, created_at) ».
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
}
