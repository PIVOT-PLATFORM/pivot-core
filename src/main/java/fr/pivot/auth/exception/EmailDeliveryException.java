package fr.pivot.auth.exception;

/**
 * Thrown when an outbound email cannot be delivered due to a messaging infrastructure failure.
 */
public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
