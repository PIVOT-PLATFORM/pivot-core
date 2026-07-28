package fr.pivot.collaboratif.exception;

/**
 * Thrown when a {@code roadmap.event.window.created} event fails structural validation (US12.4.1
 * "Error — événement malformé": missing required field, empty period, {@code fin < début}, {@code
 * durée > période}, empty {@code participants[]}).
 *
 * <p><strong>Never mapped to an HTTP response</strong> — unlike every other exception in this
 * package, this one is only ever thrown/caught inside the event-consumption path ({@code
 * meetops.bus.WindowEventListener}), which logs it (structured, no PII — see that class's
 * Javadoc) and swallows it rather than crashing the consumer or rethrowing, mirroring the AC's
 * "l'événement est rejeté [...] sans crash du consommateur". Not registered on {@code
 * CollaboratifExceptionHandler} on purpose: a REST caller can never trigger this exception (the
 * booking REST surface only ever receives already-validated, already-upserted meetings).
 */
public class MalformedWindowEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with the validation failure reason.
     *
     * @param reason a human-readable, PII-free description (never includes participant e-mails
     *               or the event title)
     */
    public MalformedWindowEventException(final String reason) {
        super(reason);
    }
}
