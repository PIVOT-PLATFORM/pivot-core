package fr.pivot.core.modules;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import fr.pivot.core.modules.event.ModuleActivatedEvent;
import fr.pivot.core.modules.event.ModuleDeactivatedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires pour {@link ModuleActivationService}.
 *
 * <p>Traçabilité EN03.1 — critères « ApplicationEventPublisher comme bus d'événements
 * inter-modules » (événements typés publiés sur transition effective) et
 * « Entité ModuleActivation » (persistance de l'état).
 */
@ExtendWith(MockitoExtension.class)
class ModuleActivationServiceTest {

    private static final Long TENANT_ID = 42L;
    private static final String MODULE_ID = "whiteboard";

    @Mock
    private ModuleRegistry moduleRegistry;

    @Mock
    private ModuleActivationRepository repository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ModuleActivationService service;

    private Level originalLevel;

    @BeforeEach
    void setUp() {
        service = new ModuleActivationService(moduleRegistry, repository, eventPublisher);
    }

    @AfterEach
    void restoreLogLevel() {
        if (originalLevel != null) {
            ((Logger) LoggerFactory.getLogger(ModuleActivationService.class)).setLevel(originalLevel);
        }
    }

    // ----------------------------------------------------------------
    // activate
    // ----------------------------------------------------------------

    @Test
    void activate_shouldPersistAndPublishActivatedEvent_whenNoExistingRow() {
        when(moduleRegistry.isRegistered(MODULE_ID)).thenReturn(true);
        when(repository.findByTenantIdAndModuleId(TENANT_ID, MODULE_ID)).thenReturn(Optional.empty());
        when(repository.save(any(ModuleActivation.class))).thenAnswer(inv -> inv.getArgument(0));

        final ModuleActivation result = service.activate(TENANT_ID, MODULE_ID);

        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(result.getModuleId()).isEqualTo(MODULE_ID);

        final ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOfSatisfying(ModuleActivatedEvent.class, event -> {
            assertThat(event.tenantId()).isEqualTo(TENANT_ID);
            assertThat(event.moduleId()).isEqualTo(MODULE_ID);
            assertThat(event.occurredAt()).isNotNull();
        });
    }

    @Test
    void activate_shouldPublishActivatedEvent_whenExistingRowDisabled() {
        final ModuleActivation existing = new ModuleActivation(TENANT_ID, MODULE_ID);
        when(moduleRegistry.isRegistered(MODULE_ID)).thenReturn(true);
        when(repository.findByTenantIdAndModuleId(TENANT_ID, MODULE_ID)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        final ModuleActivation result = service.activate(TENANT_ID, MODULE_ID);

        assertThat(result.isEnabled()).isTrue();
        verify(eventPublisher).publishEvent(any(ModuleActivatedEvent.class));
    }

    @Test
    void activate_shouldNotPublishEvent_whenAlreadyEnabled() {
        final ModuleActivation existing = new ModuleActivation(TENANT_ID, MODULE_ID);
        existing.setEnabled(true);
        when(moduleRegistry.isRegistered(MODULE_ID)).thenReturn(true);
        when(repository.findByTenantIdAndModuleId(TENANT_ID, MODULE_ID)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        service.activate(TENANT_ID, MODULE_ID);

        verify(eventPublisher, never()).publishEvent(any());
    }

    // ----------------------------------------------------------------
    // deactivate
    // ----------------------------------------------------------------

    @Test
    void deactivate_shouldPublishDeactivatedEvent_whenModuleWasEnabled() {
        final ModuleActivation existing = new ModuleActivation(TENANT_ID, MODULE_ID);
        existing.setEnabled(true);
        when(moduleRegistry.isRegistered(MODULE_ID)).thenReturn(true);
        when(repository.findByTenantIdAndModuleId(TENANT_ID, MODULE_ID)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        final ModuleActivation result = service.deactivate(TENANT_ID, MODULE_ID);

        assertThat(result.isEnabled()).isFalse();

        final ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOfSatisfying(ModuleDeactivatedEvent.class, event -> {
            assertThat(event.tenantId()).isEqualTo(TENANT_ID);
            assertThat(event.moduleId()).isEqualTo(MODULE_ID);
        });
    }

    @Test
    void deactivate_shouldPersistRowWithoutEvent_whenNoExistingRow() {
        // Absence de ligne = déjà désactivé : pas de transition, pas d'événement.
        when(moduleRegistry.isRegistered(MODULE_ID)).thenReturn(true);
        when(repository.findByTenantIdAndModuleId(TENANT_ID, MODULE_ID)).thenReturn(Optional.empty());
        when(repository.save(any(ModuleActivation.class))).thenAnswer(inv -> inv.getArgument(0));

        final ModuleActivation result = service.deactivate(TENANT_ID, MODULE_ID);

        assertThat(result.isEnabled()).isFalse();
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ----------------------------------------------------------------
    // Error case : module inconnu
    // ----------------------------------------------------------------

    @Test
    void activate_shouldThrow_whenModuleNotRegistered() {
        when(moduleRegistry.isRegistered("ghost")).thenReturn(false);

        assertThatThrownBy(() -> service.activate(TENANT_ID, "ghost"))
                .isInstanceOf(UnknownModuleException.class)
                .hasMessageContaining("ghost");

        verifyNoInteractions(repository, eventPublisher);
    }

    @Test
    void deactivate_shouldThrow_whenModuleNotRegistered() {
        when(moduleRegistry.isRegistered("ghost")).thenReturn(false);

        assertThatThrownBy(() -> service.deactivate(TENANT_ID, "ghost"))
                .isInstanceOf(UnknownModuleException.class);

        verifyNoInteractions(repository, eventPublisher);
    }

    // ----------------------------------------------------------------
    // isEnabled
    // ----------------------------------------------------------------

    @Test
    void isEnabled_shouldReturnTrue_whenRowEnabled() {
        final ModuleActivation existing = new ModuleActivation(TENANT_ID, MODULE_ID);
        existing.setEnabled(true);
        when(repository.findByTenantIdAndModuleId(TENANT_ID, MODULE_ID)).thenReturn(Optional.of(existing));

        assertThat(service.isEnabled(TENANT_ID, MODULE_ID)).isTrue();
    }

    @Test
    void isEnabled_shouldReturnFalse_whenRowDisabled() {
        final ModuleActivation existing = new ModuleActivation(TENANT_ID, MODULE_ID);
        when(repository.findByTenantIdAndModuleId(TENANT_ID, MODULE_ID)).thenReturn(Optional.of(existing));

        assertThat(service.isEnabled(TENANT_ID, MODULE_ID)).isFalse();
    }

    @Test
    void isEnabled_shouldReturnFalse_whenNoRow() {
        // Security : défaut sûr — aucune activation implicite.
        when(repository.findByTenantIdAndModuleId(TENANT_ID, MODULE_ID)).thenReturn(Optional.empty());

        assertThat(service.isEnabled(TENANT_ID, MODULE_ID)).isFalse();
    }

    // ----------------------------------------------------------------
    // Logging désactivé — sanitizeForLog() gardé par isWarnEnabled()/isInfoEnabled()
    // (java:S2629) : vérifie que le comportement métier est inchangé, quel que
    // soit le niveau de log, sur les 3 branches gardées (rejet, transition,
    // no-op).
    // ----------------------------------------------------------------

    @Test
    void changeState_shouldBehaveIdentically_whenLoggingDisabled() {
        final Logger logger = (Logger) LoggerFactory.getLogger(ModuleActivationService.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.OFF);

        when(moduleRegistry.isRegistered("ghost")).thenReturn(false);
        assertThatThrownBy(() -> service.activate(TENANT_ID, "ghost"))
                .isInstanceOf(UnknownModuleException.class);

        when(moduleRegistry.isRegistered(MODULE_ID)).thenReturn(true);
        when(repository.findByTenantIdAndModuleId(TENANT_ID, MODULE_ID)).thenReturn(Optional.empty());
        when(repository.save(any(ModuleActivation.class))).thenAnswer(inv -> inv.getArgument(0));

        final ModuleActivation activated = service.activate(TENANT_ID, MODULE_ID);
        assertThat(activated.isEnabled()).isTrue();
        verify(eventPublisher, times(1)).publishEvent(any(ModuleActivatedEvent.class));

        final ModuleActivation alreadyEnabled = new ModuleActivation(TENANT_ID, MODULE_ID);
        alreadyEnabled.setEnabled(true);
        when(repository.findByTenantIdAndModuleId(TENANT_ID, MODULE_ID)).thenReturn(Optional.of(alreadyEnabled));
        when(repository.save(alreadyEnabled)).thenReturn(alreadyEnabled);

        service.activate(TENANT_ID, MODULE_ID);
        verify(eventPublisher, times(1)).publishEvent(any(ModuleActivatedEvent.class));
    }
}
