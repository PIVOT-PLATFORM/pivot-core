package fr.pivot.tenant.api;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Slug validation policy shared by {@link SuperAdminTenantService} (create / check-slug) and
 * {@link CreateTenantRequest} (bean validation) — US06.2.1 « Super admin crée un tenant ».
 *
 * <p>Single source of truth for the format regex and the reserved-word blocklist so the two
 * call sites (create, check-slug) can never drift apart.
 */
public final class TenantSlugPolicy {

    /**
     * Strict slug format: lowercase letters, digits and hyphens, 3 to 50 characters.
     * Referenced as a compile-time constant from {@link CreateTenantRequest}'s
     * {@code @jakarta.validation.constraints.Pattern}.
     */
    public static final String SLUG_REGEX = "^[a-z0-9-]{3,50}$";

    private static final Pattern SLUG_PATTERN = Pattern.compile(SLUG_REGEX);

    /**
     * Slugs that would collide with a platform route prefix or a reserved administrative
     * term (AC US06.2.1 — liste de slugs interdits).
     */
    private static final Set<String> RESERVED_SLUGS =
            Set.of("api", "admin", "superadmin", "auth", "actuator", "health", "system", "pivot");

    private TenantSlugPolicy() {
    }

    /**
     * Checks whether {@code slug} matches the strict format {@code [a-z0-9-]\{3,50\}}.
     *
     * @param slug candidate slug, possibly {@code null}
     * @return {@code true} if the format is valid
     */
    public static boolean matchesFormat(final String slug) {
        return slug != null && SLUG_PATTERN.matcher(slug).matches();
    }

    /**
     * Checks whether {@code slug} is on the reserved-word blocklist.
     *
     * <p>Comparison is case-insensitive on the caller's behalf: a slug is only ever
     * considered here once it already matches {@link #matchesFormat(String)} (lowercase by
     * construction), but this method is defensive against direct callers passing mixed case.
     *
     * @param slug candidate slug, possibly {@code null}
     * @return {@code true} if the slug collides with a reserved platform term
     */
    public static boolean isReserved(final String slug) {
        return slug != null && RESERVED_SLUGS.contains(slug.toLowerCase(Locale.ROOT));
    }
}
