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
    void constructor_shouldDeriveSlugFromName() {
        final Team team = new Team(1L, "Squad Alpha");

        assertThat(team.getSlug()).isEqualTo("squad-alpha");
    }

    @Test
    void constructor_shouldDeriveSlug_strippingAccentsAndPunctuation() {
        final Team team = new Team(1L, "Équipe Créativité & Co !");

        assertThat(team.getSlug()).isEqualTo("equipe-creativite-co");
    }

    @Test
    void constructor_shouldDeriveSlug_strippingLeadingAndTrailingSeparators() {
        final Team team = new Team(1L, "  !Squad Alpha!  ");

        assertThat(team.getSlug()).isEqualTo("squad-alpha");
    }

    @Test
    void constructor_shouldDeriveEmptySlug_whenNameHasNoAlphanumerics() {
        final Team team = new Team(1L, "!!! ??? ---");

        assertThat(team.getSlug()).isEmpty();
    }

    @Test
    void constructor_shouldDeriveNullSlug_whenNameIsNull() {
        final Team team = new Team(1L, null);

        assertThat(team.getSlug()).isNull();
    }

    @Test
    void constructor_shouldDefaultColorAndDescriptionToNull() {
        final Team team = new Team(1L, "Squad Alpha");

        assertThat(team.getColor()).isNull();
        assertThat(team.getDescription()).isNull();
    }

    @Test
    void setSlug_shouldOverrideDerivedSlug() {
        final Team team = new Team(1L, "Squad Alpha");

        team.setSlug("custom-slug");

        assertThat(team.getSlug()).isEqualTo("custom-slug");
    }

    @Test
    void setColor_shouldStoreColor() {
        final Team team = new Team(1L, "Squad Alpha");

        team.setColor("#1E90FF");

        assertThat(team.getColor()).isEqualTo("#1E90FF");
    }

    @Test
    void setDescription_shouldStoreDescription() {
        final Team team = new Team(1L, "Squad Alpha");

        team.setDescription("Équipe transverse produit");

        assertThat(team.getDescription()).isEqualTo("Équipe transverse produit");
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
