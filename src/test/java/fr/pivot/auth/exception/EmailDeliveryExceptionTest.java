package fr.pivot.auth.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit test for {@link EmailDeliveryException}. */
class EmailDeliveryExceptionTest {

    @Test
    void carriesMessageAndCause() {
        final Throwable cause = new IllegalStateException("smtp down");
        final EmailDeliveryException ex = new EmailDeliveryException("send failed", cause);

        assertThat(ex).hasMessage("send failed").hasCause(cause);
    }
}
