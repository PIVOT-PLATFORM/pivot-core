package fr.pivot.agilite.capacity.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link CapacityKpiEventListener} (EN11.2).
 */
@ExtendWith(MockitoExtension.class)
class CapacityKpiEventListenerTest {

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Captor
    private ArgumentCaptor<KpiUpdatedEvent> eventCaptor;

    /**
     * Given a committed {@link CapacityUpdatedEvent}, when {@code onCapacityUpdated} fires, then
     * a {@link KpiUpdatedEvent} is republished carrying the same tenant/team pair.
     */
    @Test
    void onCapacityUpdated_republishesKpiUpdatedEventForSameTeam() {
        CapacityKpiEventListener listener = new CapacityKpiEventListener(applicationEventPublisher);
        CapacityUpdatedEvent event = new CapacityUpdatedEvent(1L, 2L, UUID.randomUUID(), 10.0, Instant.now());

        listener.onCapacityUpdated(event);

        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        KpiUpdatedEvent published = eventCaptor.getValue();
        assertThat(published.tenantId()).isEqualTo(event.tenantId());
        assertThat(published.teamRef()).isEqualTo(event.teamRef());
        assertThat(published.occurredAt()).isNotNull();
    }
}
