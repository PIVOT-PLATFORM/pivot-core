package fr.pivot.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Payload for POST /auth/login.
 *
 * @param email             user email address (normalized to lowercase before processing)
 * @param password          plaintext password (BCrypt-verified, never logged)
 * @param deviceFingerprint browser fingerprint for trusted-device MFA check (optional)
 * @param deviceName        human-readable device label, e.g. "Chrome · Windows" (optional)
 * @param rememberMe        if {@code true}, issues a 30-day session instead of 24 h
 */
public record LoginRequest(
    @NotBlank @Email String email,
    @NotBlank String password,
    String deviceFingerprint,
    String deviceName,
    Boolean rememberMe
) {}
