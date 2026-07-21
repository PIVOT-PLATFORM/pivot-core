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
 * Unit tests for {@link CapacityUpdatedEventPublisher} (EN11.2).
 */
@ExtendWith(MockitoExtension.class)
class CapacityUpdatedEventPublisherTest {

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Captor
    private ArgumentCaptor<CapacityUpdatedEvent> eventCaptor;

    /**
     * Given a {@link CapacityUpdatedEvent}, when {@code publish} is called, then it is forwarded
     * unchanged to Spring's {@link ApplicationEventPublisher}.
     */
    @Test
    void publish_forwardsEventUnchanged() {
        CapacityUpdatedEventPublisher publisher = new CapacityUpdatedEventPublisher(applicationEventPublisher);
        UUID sprintRef = UUID.randomUUID();
        CapacityUpdatedEvent event = new CapacityUpdatedEvent(1L, 2L, sprintRef, 42.5, Instant.now());

        publisher.publish(event);

        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isEqualTo(event);
    }
}
