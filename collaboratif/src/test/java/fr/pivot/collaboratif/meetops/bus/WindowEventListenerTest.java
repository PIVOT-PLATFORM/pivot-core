package fr.pivot.collaboratif.meetops.bus;

import fr.pivot.collaboratif.exception.MalformedWindowEventException;
import fr.pivot.collaboratif.meetops.booking.BookingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link WindowEventListener} (US12.4.1 "Error — événement malformé") — verifies
 * that a {@link MalformedWindowEventException} thrown by {@link BookingService} is swallowed
 * (logged, not rethrown), so a malformed upstream event never crashes the consumer.
 */
@ExtendWith(MockitoExtension.class)
class WindowEventListenerTest {

    @Mock
    private BookingService bookingService;

    @Test
    void onWindowCreated_malformedEvent_doesNotPropagateException() {
        WindowEventListener localListener = new WindowEventListener(bookingService);
        WindowCreatedEvent event = new WindowCreatedEvent(
                1L, "evt-1", null, "Title", List.of(), Instant.now(), Instant.now().plusSeconds(3600), 30);
        doThrow(new MalformedWindowEventException("participants must not be empty"))
                .when(bookingService).consumeWindowCreated(event);

        assertThatCode(() -> localListener.onWindowCreated(event)).doesNotThrowAnyException();
        verify(bookingService).consumeWindowCreated(event);
    }

    @Test
    void onWindowUpdated_malformedEvent_doesNotPropagateException() {
        WindowEventListener localListener = new WindowEventListener(bookingService);
        WindowUpdatedEvent event = new WindowUpdatedEvent(
                1L, "evt-1", null, "Title", List.of(), Instant.now(), Instant.now().plusSeconds(3600), 30);
        doThrow(new MalformedWindowEventException("participants must not be empty"))
                .when(bookingService).consumeWindowUpdated(event);

        assertThatCode(() -> localListener.onWindowUpdated(event)).doesNotThrowAnyException();
        verify(bookingService).consumeWindowUpdated(event);
    }

    @Test
    void onWindowDeleted_delegatesDirectly_noExceptionSwallowing() {
        WindowEventListener localListener = new WindowEventListener(bookingService);
        WindowDeletedEvent event = new WindowDeletedEvent(1L, "evt-1");

        localListener.onWindowDeleted(event);

        verify(bookingService).consumeWindowDeleted(event);
    }
}
