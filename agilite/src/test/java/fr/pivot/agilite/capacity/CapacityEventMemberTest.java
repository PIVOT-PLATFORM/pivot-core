package fr.pivot.agilite.capacity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour l'entité {@link CapacityEventMember} (E11 — capacity planning).
 */
class CapacityEventMemberTest {

    @Test
    void constructor_shouldSetCoreFields() {
        final UUID eventId = UUID.randomUUID();

        final CapacityEventMember member = new CapacityEventMember(eventId, 42L, "Alice", "Dev", 1.0, 0);

        assertThat(member.getId()).isNull();
        assertThat(member.getEventId()).isEqualTo(eventId);
        assertThat(member.getTeamMemberRef()).isEqualTo(42L);
        assertThat(member.getName()).isEqualTo("Alice");
        assertThat(member.getRole()).isEqualTo("Dev");
        assertThat(member.getQuotite()).isEqualTo(1.0);
        assertThat(member.getPosition()).isZero();
    }

    @Test
    void constructor_shouldDefaultExcludedToFalse() {
        final CapacityEventMember member = new CapacityEventMember(UUID.randomUUID(), null, "Bob", null, 0.5, 1);

        assertThat(member.isExcluded()).isFalse();
    }

    @Test
    void constructor_shouldAllowNullTeamMemberRef_forFreeTextMember() {
        final CapacityEventMember member = new CapacityEventMember(UUID.randomUUID(), null, "Bob", null, 0.5, 1);

        assertThat(member.getTeamMemberRef()).isNull();
        assertThat(member.getRole()).isNull();
    }

    @Test
    void constructor_shouldDefaultFocusFactorAndLocalityToNull() {
        final CapacityEventMember member = new CapacityEventMember(UUID.randomUUID(), 1L, "Alice", "Dev", 1.0, 0);

        assertThat(member.getFocusFactor()).isNull();
        assertThat(member.getLocality()).isNull();
    }

    @Test
    void setName_shouldStoreName() {
        final CapacityEventMember member = new CapacityEventMember(UUID.randomUUID(), 1L, "Alice", "Dev", 1.0, 0);

        member.setName("Alicia");

        assertThat(member.getName()).isEqualTo("Alicia");
    }

    @Test
    void setRole_shouldStoreRole() {
        final CapacityEventMember member = new CapacityEventMember(UUID.randomUUID(), 1L, "Alice", "Dev", 1.0, 0);

        member.setRole("Lead Dev");

        assertThat(member.getRole()).isEqualTo("Lead Dev");
    }

    @Test
    void setQuotite_shouldStoreQuotite() {
        final CapacityEventMember member = new CapacityEventMember(UUID.randomUUID(), 1L, "Alice", "Dev", 1.0, 0);

        member.setQuotite(0.8);

        assertThat(member.getQuotite()).isEqualTo(0.8);
    }

    @Test
    void setFocusFactor_shouldStoreOverride() {
        final CapacityEventMember member = new CapacityEventMember(UUID.randomUUID(), 1L, "Alice", "Dev", 1.0, 0);

        member.setFocusFactor(0.9);

        assertThat(member.getFocusFactor()).isEqualTo(0.9);
    }

    @Test
    void setLocality_shouldStoreLocality() {
        final CapacityEventMember member = new CapacityEventMember(UUID.randomUUID(), 1L, "Alice", "Dev", 1.0, 0);

        member.setLocality("Paris");

        assertThat(member.getLocality()).isEqualTo("Paris");
    }

    @Test
    void setExcluded_shouldExcludeMember() {
        final CapacityEventMember member = new CapacityEventMember(UUID.randomUUID(), 1L, "Alice", "Dev", 1.0, 0);

        member.setExcluded(true);

        assertThat(member.isExcluded()).isTrue();
    }

    @Test
    void setPosition_shouldReorderMember() {
        final CapacityEventMember member = new CapacityEventMember(UUID.randomUUID(), 1L, "Alice", "Dev", 1.0, 0);

        member.setPosition(3);

        assertThat(member.getPosition()).isEqualTo(3);
    }
}
