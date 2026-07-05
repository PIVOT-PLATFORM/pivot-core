package fr.pivot.tenant.api;

/**
 * Response body of {@code GET /api/superadmin/tenants/check-slug} — US06.2.1.
 *
 * <p>Backs the Angular form's real-time (debounced 500ms) availability check while the super
 * admin edits the slug field.
 *
 * @param available {@code true} if the slug can be used to create a tenant right now
 * @param reason    {@code null} when {@code available} is {@code true}; otherwise one of
 *                  {@code INVALID_FORMAT}, {@code RESERVED}, {@code TAKEN}
 */
public record SlugAvailabilityResponse(boolean available, String reason) {

    private static final String INVALID_FORMAT = "INVALID_FORMAT";
    private static final String RESERVED = "RESERVED";
    private static final String TAKEN = "TAKEN";

    /**
     * @return a response indicating the slug is available
     */
    public static SlugAvailabilityResponse ofAvailable() {
        return new SlugAvailabilityResponse(true, null);
    }

    /**
     * @return a response indicating the slug fails the strict format regex
     */
    public static SlugAvailabilityResponse invalidFormat() {
        return new SlugAvailabilityResponse(false, INVALID_FORMAT);
    }

    /**
     * @return a response indicating the slug collides with a reserved platform term
     */
    public static SlugAvailabilityResponse reserved() {
        return new SlugAvailabilityResponse(false, RESERVED);
    }

    /**
     * @return a response indicating the slug is already used by another tenant
     */
    public static SlugAvailabilityResponse taken() {
        return new SlugAvailabilityResponse(false, TAKEN);
    }
}
