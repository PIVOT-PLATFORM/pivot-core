package fr.pivot.core.modules;

import fr.pivot.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests unitaires pour {@link ModuleRegistry}.
 *
 * <p>Traçabilité EN03.1 — critère « ModuleRegistry : enregistrement, lookup,
 * liste des modules disponibles ».
 */
class ModuleRegistryTest {

    private static PivotModule module(final String id, final String name, final String version) {
        return new PivotModule() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getVersion() {
                return version;
            }

            @Override
            public String getDescription() {
                return "";
            }

            @Override
            public boolean isEnabled(final TenantContext ctx) {
                return true;
            }
        };
    }

    @Test
    void shouldBeEmpty_whenNoModulesDiscovered() {
        final ModuleRegistry registry = new ModuleRegistry(List.of());

        assertThat(registry.getModules()).isEmpty();
        assertThat(registry.count()).isZero();
        assertThat(registry.findById("whiteboard")).isEmpty();
        assertThat(registry.isRegistered("whiteboard")).isFalse();
    }

    @Test
    void shouldLookupModuleById_whenRegistered() {
        final PivotModule whiteboard = module("whiteboard", "Tableau blanc", "1.0.0");
        final ModuleRegistry registry = new ModuleRegistry(List.of(whiteboard));

        assertThat(registry.findById("whiteboard")).containsSame(whiteboard);
        assertThat(registry.isRegistered("whiteboard")).isTrue();
        assertThat(registry.count()).isEqualTo(1);
    }

    @Test
    void shouldReturnEmptyOptional_whenModuleUnknown() {
        final ModuleRegistry registry = new ModuleRegistry(List.of(module("quiz", "Quiz", "0.1.0")));

        assertThat(registry.findById("unknown")).isEmpty();
        assertThat(registry.isRegistered("unknown")).isFalse();
    }

    @Test
    void shouldListModulesInDiscoveryOrder() {
        final PivotModule first = module("roadmap", "Roadmap", "1.0.0");
        final PivotModule second = module("quiz", "Quiz", "0.1.0");
        final ModuleRegistry registry = new ModuleRegistry(List.of(first, second));

        assertThat(registry.getModules()).containsExactly(first, second);
    }

    @Test
    void getModules_shouldReturnImmutableList() {
        final ModuleRegistry registry = new ModuleRegistry(List.of(module("quiz", "Quiz", "0.1.0")));

        final List<PivotModule> modules = registry.getModules();
        final PivotModule hack = module("hack", "Hack", "0.0.1");

        assertThatThrownBy(() -> modules.add(hack))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldFailFast_whenDuplicateModuleId() {
        final List<PivotModule> duplicates = List.of(
                module("quiz", "Quiz A", "1.0.0"),
                module("quiz", "Quiz B", "2.0.0"));

        assertThatThrownBy(() -> new ModuleRegistry(duplicates))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quiz");
    }
}
