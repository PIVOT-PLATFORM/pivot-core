package fr.pivot.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record OidcExchangeRequest(
    @NotBlank String tenantSlug,
    @NotBlank String accessToken,
    String deviceFingerprint,
    String deviceName
) {}
