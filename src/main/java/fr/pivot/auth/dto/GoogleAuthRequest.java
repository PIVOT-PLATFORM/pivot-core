package fr.pivot.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record GoogleAuthRequest(
    @NotBlank String idToken,
    String deviceFingerprint,
    String deviceName
) {}
