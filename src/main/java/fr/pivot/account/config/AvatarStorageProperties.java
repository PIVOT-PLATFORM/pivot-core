package fr.pivot.account.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Local filesystem avatar storage configuration (US02.1.1), bound from
 * {@code pivot.storage.avatars.*}.
 *
 * <p><strong>MVP storage choice:</strong> avatars are stored on the local filesystem, not an
 * object store (S3/GCS/etc.) — PIVOT targets self-hosted deployment first (see
 * {@code CLAUDE.md} — "auto-hébergeable"), and a cloud dependency would be a heavier default
 * than the MVP needs. {@code basePath} is the only configurable knob; swapping to a
 * pluggable {@code AvatarStorageService} (S3-backed, etc.) later is a drop-in replacement that
 * does not change the {@code ProfileService}/{@code AccountController} contract.
 *
 * @param basePath root directory under which per-tenant avatar subdirectories are created.
 *                 Defaults to a path relative to the process working directory — override with
 *                 {@code PIVOT_AVATAR_STORAGE_PATH} in any real deployment (Docker volume mount).
 */
@ConfigurationProperties("pivot.storage.avatars")
public record AvatarStorageProperties(
    @DefaultValue("./data/avatars") String basePath
) {
}
