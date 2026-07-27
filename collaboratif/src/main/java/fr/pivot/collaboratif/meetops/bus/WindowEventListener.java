package fr.pivot.collaboratif.meetops.bus;

import fr.pivot.collaboratif.exception.MalformedWindowEventException;
import fr.pivot.collaboratif.meetops.booking.BookingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@link WindowCreatedEvent}/{@link WindowUpdatedEvent}/{@link WindowDeletedEvent}
 * (US12.4.1) — the in-process boundary a future integration agent bridges onto the real
 * {@code roadmap.event.window.*} bus event once EPIC-roadmap (US22.8.6) actually publishes it
 * (out of this sprint's scope, see {@link WindowCreatedEvent}'s Javadoc). Until then, this
 * listener is exercised directly by tests via {@code ApplicationEventPublisher#publishEvent}
 * ("TI publiant directement", per the Gate 1 architecture note) or by a future real relay
 * listener translating the external bus frame into one of these events.
 *
 * <p><strong>"Error — événement malformé" (US12.4.1 AC).</strong> A structurally invalid payload
 * throws {@link MalformedWindowEventException} from {@link BookingService}; this listener catches
 * it here, logs a single structured, PII-free line (tenant + event ref + reason only — never the
 * event's title or {@code participants[]}, which may carry personal e-mail addresses) and returns
 * normally. Spring's default synchronous {@code ApplicationEventMulticaster} would otherwise
 * propagate the exception back to whichever code called {@code publishEvent} — catching it here
 * is what makes "l'événement est rejeté [...] sans crash du consommateur" hold for every caller,
 * not just a hypothetical asynchronous relay. There is no broker-level Dead Letter Queue in this
 * in-process boundary (that only exists on the real ActiveMQ relay, {@code DLQ.collaboratif}) —
 * "rejected without a DLQ" is the accurate, accepted scope for this sprint; a future real bus
 * listener would additionally route the raw frame to {@code DLQ.collaboratif} instead of just
 * logging, which this in-process consumer has no equivalent mechanism for.
 */
@Component
public class WindowEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(WindowEventListener.class);

    private final BookingService bookingService;

    /**
     * Creates the listener with its required dependency.
     *
     * @param bookingService the business logic service handling the actual consumption
     */
    public WindowEventListener(final BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /**
     * Handles {@code window.created} — creates or upserts the {@code PRE_RESERVED} meeting.
     *
     * @param event the event
     */
    @EventListener
    public void onWindowCreated(final WindowCreatedEvent event) {
        try {
            bookingService.consumeWindowCreated(event);
        } catch (MalformedWindowEventException e) {
            LOG.warn("Rejected malformed window.created: tenant={} eventRef={} reason={}",
                    event.tenantId(), event.eventRef(), e.getMessage());
        }
    }

    /**
     * Handles {@code window.updated} — recomputes a non-confirmed meeting, or raises a
     * reprogramming request for an already-confirmed one.
     *
     * @param event the event
     */
    @EventListener
    public void onWindowUpdated(final WindowUpdatedEvent event) {
        try {
            bookingService.consumeWindowUpdated(event);
        } catch (MalformedWindowEventException e) {
            LOG.warn("Rejected malformed window.updated: tenant={} eventRef={} reason={}",
                    event.tenantId(), event.eventRef(), e.getMessage());
        }
    }

    /**
     * Handles {@code window.deleted} — cancels a non-confirmed meeting, or raises a reprogramming
     * request for an already-confirmed one.
     *
     * @param event the event
     */
    @EventListener
    public void onWindowDeleted(final WindowDeletedEvent event) {
        bookingService.consumeWindowDeleted(event);
    }
}
