package fr.pivot.agilite.standup.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for extending the current speaker's time (US10.2.2, {@code POST .../extend}).
 *
 * <p>{@code seconds} must be exactly {@code 30} or {@code 60} — checked at the service layer
 * ({@code INVALID_EXTEND_SECONDS}) since bean validation has no built-in "one of these two
 * values" constraint.
 *
 * @param seconds the number of seconds to add, {@code 30} or {@code 60}
 */
public record ExtendTimerRequest(@NotNull(message = "INVALID_EXTEND_SECONDS") Integer seconds) {
}
