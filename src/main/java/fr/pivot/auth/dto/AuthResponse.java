package fr.pivot.auth.dto;

public record AuthResponse(
    String accessToken,
    long expiresAt,
    UserInfo user
) {
    public record UserInfo(
        Long id,
        String email,
        String firstName,
        String lastName,
        String role,
        boolean emailVerified,
        Long tenantId,
        String tenantSlug
    ) {}
}
