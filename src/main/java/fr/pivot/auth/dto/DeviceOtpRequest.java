package fr.pivot.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Payload for POST /auth/device/verify.
 *
 * @param deviceFingerprint browser fingerprint that triggered the OTP flow
 * @param otp               6-digit one-time password sent by email
 * @param deviceName        human-readable device label (optional)
 * @param rememberMe        carries the user's preference from the original login request
 */
public record DeviceOtpRequest(
    @NotBlank String deviceFingerprint,
    @NotBlank @Pattern(regexp = "\\d{6}") String otp,
    String deviceName,
    boolean rememberMe
) {}
