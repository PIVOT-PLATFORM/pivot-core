package fr.pivot.contact.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO for public contact form submissions.
 *
 * @param email   sender's email address — validated, max 255 chars
 * @param message free-text message body — required, max 2 000 chars
 * @param lang    preferred locale tag ("fr" or "en") — optional, defaults to French
 */
public record ContactRequestDto(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 2000) String message,
        String lang
) {}
