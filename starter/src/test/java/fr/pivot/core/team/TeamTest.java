package fr.pivot.core.team;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour l'entité {@link Team}.
 *
 * <p>Traçabilité EN17.1 (volet team, pivot-core#171) — critère « Entité Team en BDD
 * (tenant_id, name, timestamps) ».
 */
class TeamTest {

    @Test
    void constructor_shouldSetTenantIdAndName() {
        final Team team = new Team(1L, "Squad Alpha");

        assertThat(team.getId()).isNull();
        assertThat(team.getTenantId()).isEqualTo(1L);
        assertThat(team.getName()).isEqualTo("Squad Alpha");
        assertThat(team.getCreatedAt()).isNotNull();
        assertThat(team.getUpdatedAt()).isNotNull();
    }

    @Test
    void constructor_shouldDefaultToOrphan_parentTeamIdNull() {
        final Team team = new Team(1L, "Squad Alpha");

        assertThat(team.getParentTeamId()).isNull();
    }

    @Test
    void setParentTeamId_shouldAttachToParentTeam() {
        final Team team = new Team(1L, "Squad Alpha");

        team.setParentTeamId(99L);

        assertThat(team.getParentTeamId()).isEqualTo(99L);
    }

    @Test
    void setParentTeamId_shouldDetachBackToOrphan_whenSetToNull() {
        final Team team = new Team(1L, "Squad Alpha");
        team.setParentTeamId(99L);

        team.setParentTeamId(null);

        assertThat(team.getParentTeamId()).isNull();
    }

    @Test
    void setName_shouldRenameTeam() {
        final Team team = new Team(1L, "Squad Alpha");

        team.setName("Squad Bravo");

        assertThat(team.getName()).isEqualTo("Squad Bravo");
    }

    @Test
    void onUpdate_shouldRefreshUpdatedAt() {
        final Team team = new Team(1L, "Squad Alpha");
        final Instant before = team.getUpdatedAt();

        team.onUpdate();

        assertThat(team.getUpdatedAt()).isAfterOrEqualTo(before);
        assertThat(team.getCreatedAt()).isBeforeOrEqualTo(team.getUpdatedAt());
    }
}
