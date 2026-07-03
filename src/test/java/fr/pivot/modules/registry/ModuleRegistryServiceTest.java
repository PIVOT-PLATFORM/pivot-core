package fr.pivot.modules.registry;

import fr.pivot.core.modules.ModuleRegistry;
import fr.pivot.core.modules.PivotModule;
import fr.pivot.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires pour {@link ModuleRegistryService}.
 *
 * <p>Vérifie la logique de projection {@link PivotModule} → {@link ModuleDto} :
 * activation, désactivation et liste vide — via un {@link ModuleRegistry} réel
 * construit sur des modules mockés.
 */
@ExtendWith(MockitoExtension.class)
class ModuleRegistryServiceTest {

    @Mock
    private PivotModule enabledModule;

    @Mock
    private PivotModule disabledModule;

    private static final TenantContext CTX = new TenantContext(1L, "42", "ROLE_USER");

    private static ModuleRegistryService serviceWith(final List<PivotModule> modules) {
        return new ModuleRegistryService(new ModuleRegistry(modules));
    }

    @Test
    void shouldReturnEmptyList_whenNoModulesRegistered() {
        final ModuleRegistryService service = serviceWith(List.of());

        final List<ModuleDto> result = service.getModulesForTenant(CTX);

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnOnlineDto_whenModuleIsEnabled() {
        when(enabledModule.getId()).thenReturn("whiteboard");
        when(enabledModule.getName()).thenReturn("Tableau blanc");
        when(enabledModule.getVersion()).thenReturn("1.0.0");
        when(enabledModule.isEnabled(CTX)).thenReturn(true);

        final ModuleRegistryService service = serviceWith(List.of(enabledModule));

        final List<ModuleDto> result = service.getModulesForTenant(CTX);

        assertThat(result).hasSize(1);
        final ModuleDto dto = result.get(0);
        assertThat(dto.id()).isEqualTo("whiteboard");
        assertThat(dto.name()).isEqualTo("Tableau blanc");
        assertThat(dto.version()).isEqualTo("1.0.0");
        assertThat(dto.enabled()).isTrue();
        assertThat(dto.status()).isEqualTo(ModuleStatus.ONLINE);
    }

    @Test
    void shouldReturnOfflineDto_whenModuleIsDisabled() {
        when(disabledModule.getId()).thenReturn("quiz");
        when(disabledModule.getName()).thenReturn("Quiz");
        when(disabledModule.getVersion()).thenReturn("0.1.0");
        when(disabledModule.isEnabled(CTX)).thenReturn(false);

        final ModuleRegistryService service = serviceWith(List.of(disabledModule));

        final List<ModuleDto> result = service.getModulesForTenant(CTX);

        assertThat(result).hasSize(1);
        final ModuleDto dto = result.get(0);
        assertThat(dto.id()).isEqualTo("quiz");
        assertThat(dto.enabled()).isFalse();
        assertThat(dto.status()).isEqualTo(ModuleStatus.OFFLINE);
    }

    @Test
    void shouldProjectAllModules_whenMixedEnabledAndDisabled() {
        when(enabledModule.getId()).thenReturn("whiteboard");
        when(enabledModule.getName()).thenReturn("Tableau blanc");
        when(enabledModule.getVersion()).thenReturn("1.0.0");
        when(enabledModule.isEnabled(CTX)).thenReturn(true);

        when(disabledModule.getId()).thenReturn("survey");
        when(disabledModule.getName()).thenReturn("Sondage");
        when(disabledModule.getVersion()).thenReturn("0.2.0");
        when(disabledModule.isEnabled(CTX)).thenReturn(false);

        final ModuleRegistryService service = serviceWith(List.of(enabledModule, disabledModule));

        final List<ModuleDto> result = service.getModulesForTenant(CTX);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).enabled()).isTrue();
        assertThat(result.get(0).status()).isEqualTo(ModuleStatus.ONLINE);
        assertThat(result.get(1).enabled()).isFalse();
        assertThat(result.get(1).status()).isEqualTo(ModuleStatus.OFFLINE);
    }
}
