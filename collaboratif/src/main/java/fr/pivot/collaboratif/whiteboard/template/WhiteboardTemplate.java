package fr.pivot.collaboratif.whiteboard.template;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity representing a whiteboard template header (US08.4.1).
 *
 * <p>Global (public, {@code tenant_id IS NULL}) rows are exclusively seeded via Flyway
 * ({@code V1__schema_init.sql}) — there is no user-facing endpoint to create, update, or
 * delete a <em>global</em> template in the Socle (see the US "Hors périmètre" section).
 *
 * <p>{@code tenantId} was originally {@code null} for every row produced in the Socle
 * (global public templates only); US08.2.4 ("Enregistrer comme template") introduces the
 * first application-created rows, with a non-null {@code tenantId} — a private, per-tenant
 * template distinct from the 3 seeded global ones. The {@link #WhiteboardTemplate(UUID, Long,
 * String, String, String)} constructor exists for that write path; the no-arg constructor
 * remains for JPA hydration of both seeded and application-created rows alike.
 */
@Entity
@Table(name = "whiteboard_template", schema = "collaboratif")
public class WhiteboardTemplate {

    /** Fixed UUID assigned in the Flyway seed data. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Owning tenant, or {@code null} for a global public template. */
    @Column(name = "tenant_id", updatable = false)
    private Long tenantId;

    /** Stable machine-readable identifier (e.g. {@code "BRAINSTORM"}), used by the
     * frontend as an i18n key prefix ({@code whiteboard.template.<code>.*}). */
    @Column(name = "code", nullable = false, length = 50, updatable = false)
    private String code;

    /** Default display name (fallback if the frontend i18n lookup misses). */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** Default short description (fallback if the frontend i18n lookup misses). */
    @Column(name = "description", length = 500)
    private String description;

    /** URL of the template's gallery preview image, or {@code null} if none. */
    @Column(name = "thumbnail_url", length = 255)
    private String thumbnailUrl;

    /** Ordering position within the template gallery. */
    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /**
     * Owner of a personal template (US08.13.2), or {@code null} for a seeded global template.
     *
     * <p>Nullable rather than mandatory because the 10 global templates shipped with the product
     * have no author. A {@code null} owner therefore means "belongs to everyone", never "orphaned".
     */
    @Column(name = "owner_id", updatable = false)
    private Long ownerId;

    /** Server-side creation timestamp. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * Last content or metadata change (US08.13.2).
     *
     * <p>Distinct from {@link #createdAt}: {@code save-from-draft} rewrites a template's content
     * without creating it, and the gallery orders personal templates by most-recently-edited.
     */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** No-arg constructor required by JPA. */
    protected WhiteboardTemplate() {
    }

    /**
     * Creates a new tenant-owned (private) template — used by the "save as template"
     * flow (US08.2.4). {@code code} is derived from {@code id} to satisfy the column's
     * {@code UNIQUE NOT NULL} constraint without colliding with any seeded global template's
     * fixed code.
     *
     * @param id          server-generated UUID
     * @param tenantId    owning tenant's {@code public.tenants.id} (never {@code null} for
     *                    application-created templates)
     * @param name        display name (1–100 chars, validated at the controller layer)
     * @param description optional short description (up to 500 chars), or {@code null}
     * @param thumbnailUrl gallery preview image URL, or {@code null}
     */
    public WhiteboardTemplate(
            final UUID id,
            final Long tenantId,
            final Long ownerId,
            final String name,
            final String description,
            final String thumbnailUrl) {
        this.id = id;
        this.tenantId = tenantId;
        this.ownerId = ownerId;
        this.code = "CUSTOM_" + id;
        this.name = name;
        this.description = description;
        this.thumbnailUrl = thumbnailUrl;
        this.displayOrder = 0;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Returns the personal owner of this template, or {@code null} for a global one.
     *
     * @return the owner's {@code public.users.id}, or {@code null}
     */
    public Long getOwnerId() {
        return ownerId;
    }

    /**
     * Returns the last time this template's content or metadata changed.
     *
     * @return the update timestamp
     */
    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Renames the template and stamps it as updated.
     *
     * @param name the new display name
     */
    public void rename(final String name) {
        this.name = name;
        touch();
    }

    /**
     * Replaces the optional short description and stamps the template as updated.
     *
     * @param description the new description, or {@code null} to clear it
     */
    public void setDescription(final String description) {
        this.description = description;
        touch();
    }

    /**
     * Marks the template as changed now.
     *
     * <p>Called explicitly rather than through a JPA lifecycle callback: rewriting a template's
     * elements does not touch the template row itself, so {@code @PreUpdate} would never fire for
     * the very operation ({@code save-from-draft}) the timestamp exists to record.
     */
    public void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * Returns the template's unique identifier.
     *
     * @return the UUID
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the owning tenant, or {@code null} for a global public template.
     *
     * @return the tenant's {@code public.tenants.id}, or {@code null}
     */
    public Long getTenantId() {
        return tenantId;
    }

    /**
     * Returns the stable machine-readable template code.
     *
     * @return the code
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the default display name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the default short description.
     *
     * @return the description, or {@code null}
     */
    public String getDescription() {
        return description;
    }

    /**
     * Returns the gallery preview image URL.
     *
     * @return the thumbnail URL, or {@code null}
     */
    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    /**
     * Returns the display order within the template gallery.
     *
     * @return the display order
     */
    public int getDisplayOrder() {
        return displayOrder;
    }

    /**
     * Returns the creation timestamp.
     *
     * @return the createdAt instant
     */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
