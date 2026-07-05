package fr.pivot.auth.dto;

public record AuthResponse(
    String accessToken,
    long expiresAt,
    UserInfo user
) {
    /**
     * {@code preferredLanguage} (US02.1.2) lets the frontend apply the user's saved UI
     * language (Transloco) immediately on login, without waiting for a follow-up
     * {@code GET /account/profile} call — always {@code "fr"} or {@code "en"}.
     */
    public record UserInfo(
        Long id,
        String email,
        String firstName,
        String lastName,
        String role,
        boolean emailVerified,
        Long tenantId,
        String tenantSlug,
        String preferredLanguage
    ) {}
}
