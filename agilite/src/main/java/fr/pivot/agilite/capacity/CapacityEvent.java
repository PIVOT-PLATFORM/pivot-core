package fr.pivot.agilite.capacity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA entity backing a capacity-tracked event (PI planning, sprint, release, or a custom event)
 * (E11 — capacity planning), table {@code agilite.capacity_event}.
 *
 * <p>Ported from the PouetPouet POC's {@code CapacityEvent} Prisma model (apps/api's {@code
 * 20260606000000_add_capacity_planning} migration) and {@code CapacityEvent} TypeScript interface
 * (apps/web's {@code capacity.ts}), adapted to this repo's {@code BIGINT} tenant/team FK + {@code
 * UUID} resource-id conventions ({@code tenantId}/{@code teamId} reference {@code
 * public.tenants}/{@code public.teams}, never a UUID — see {@link
 * fr.pivot.agilite.context.RequestPrincipal}'s Javadoc).
 *
 * <p><strong>Wave 0 scope.</strong> This US ships only the schema, this entity, its repository,
 * and the pure {@code CapacityCalculator} engine — no controller/service exists yet, so no
 * business-rule enforcement (depth-2 parent nesting, IP-sprint consolidation, tenant/role checks)
 * happens here; those belong to a later wave's service layer.
 *
 * <p>{@code maturityLevel}/{@code focusFactor}/{@code margeSecurite}/{@code pointsPerDay} are all
 * nullable per-event overrides — {@code null} means "use {@code CapacityCalculator}'s built-in
 * default" (see its Javadoc for the exact default maturity profile table).
 */
@Entity
@Table(name = "capacity_event", schema = "agilite")
public class CapacityEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private CapacityEventType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CapacityEventStatus status;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** Parent PI, for a sprint that belongs to one — {@code null} for a root-level event. */
    @Column(name = "parent_id")
    private UUID parentId;

    /**
     * Whether this sprint is a SAFe Innovation &amp; Planning sprint — excluded from its parent
     * PI's consolidated capacity by {@code CapacityCalculator.consolidatePi}.
     */
    @Column(name = "is_ip_sprint", nullable = false)
    private boolean ipSprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "maturity_level", length = 20)
    private CapacityMaturityLevel maturityLevel;

    /** Event-level default focus factor override, in {@code [0, 1]} — {@code null} = unset. */
    @Column(name = "focus_factor")
    private Double focusFactor;

    /** Safety margin applied to the recommended engagement, in {@code [0, 1]} — {@code null} = unset. */
    @Column(name = "marge_securite")
    private Double margeSecurite;

    /** Story points per net person-day, for the points conversion — {@code null} = unset. */
    @Column(name = "points_per_day")
    private Double pointsPerDay;

    @Column(name = "committed_points")
    private Double committedPoints;

    @Column(name = "completed_points")
    private Double completedPoints;

    /** Weekdays counted as working days, {@code 0} = Sunday .. {@code 6} = Saturday. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "working_days", nullable = false, columnDefinition = "integer[]")
    private Integer[] workingDays;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** No-argument constructor required by JPA. */
    protected CapacityEvent() {
    }

    /**
     * Creates a new capacity event ready to persist. {@code status} always starts {@link
     * CapacityEventStatus#PLANNING} and is not settable from outside this constructor;
     * {@code committedPoints}/{@code completedPoints}/{@code parentId} start {@code null} and are
     * populated later via the (future) service layer.
     *
     * @param tenantId    the owning tenant, resolved server-side from the caller's token
     * @param teamId      the owning PIVOT team ({@code public.teams.id})
     * @param type        the kind of event
     * @param name        the event's display name
     * @param startDate   the event's first calendar day (inclusive)
     * @param endDate     the event's last calendar day (inclusive), never before {@code startDate}
     * @param workingDays weekdays counted as working days, {@code 0} (Sunday) .. {@code 6} (Saturday)
     */
    public CapacityEvent(
            final Long tenantId,
            final Long teamId,
            final CapacityEventType type,
            final String name,
            final LocalDate startDate,
            final LocalDate endDate,
            final Integer[] workingDays) {
        this.tenantId = tenantId;
        this.teamId = teamId;
        this.type = type;
        this.status = CapacityEventStatus.PLANNING;
        this.name = name;
        this.startDate = startDate;
        this.endDate = endDate;
        this.workingDays = workingDays;
        this.ipSprint = false;
    }

    /** Stamps {@link #createdAt}/{@link #updatedAt} on first insert. */
    @PrePersist
    void onCreate() {
        final Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Bumps {@link #updatedAt} before every update. */
    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** @return database primary key */
    public UUID getId() {
        return id;
    }

    /** @return the owning tenant's {@code public.tenants.id} */
    public Long getTenantId() {
        return tenantId;
    }

    /** @return the owning PIVOT team's {@code public.teams.id} */
    public Long getTeamId() {
        return teamId;
    }

    /** @return the kind of event */
    public CapacityEventType getType() {
        return type;
    }

    /** @return the event's current lifecycle status */
    public CapacityEventStatus getStatus() {
        return status;
    }

    /** @param status the new lifecycle status */
    public void setStatus(final CapacityEventStatus status) {
        this.status = status;
    }

    /** @return the event's display name */
    public String getName() {
        return name;
    }

    /** @return the event's first calendar day (inclusive) */
    public LocalDate getStartDate() {
        return startDate;
    }

    /** @return the event's last calendar day (inclusive) */
    public LocalDate getEndDate() {
        return endDate;
    }

    /** @return the parent PI's identifier, or {@code null} for a root-level event */
    public UUID getParentId() {
        return parentId;
    }

    /** @param parentId the parent PI's identifier, or {@code null} to detach */
    public void setParentId(final UUID parentId) {
        this.parentId = parentId;
    }

    /** @return {@code true} if this sprint is a SAFe Innovation &amp; Planning sprint */
    public boolean isIpSprint() {
        return ipSprint;
    }

    /** @param ipSprint whether this sprint is a SAFe Innovation &amp; Planning sprint */
    public void setIpSprint(final boolean ipSprint) {
        this.ipSprint = ipSprint;
    }

    /** @return the team maturity level override, or {@code null} if unset */
    public CapacityMaturityLevel getMaturityLevel() {
        return maturityLevel;
    }

    /** @param maturityLevel the team maturity level override, or {@code null} to unset */
    public void setMaturityLevel(final CapacityMaturityLevel maturityLevel) {
        this.maturityLevel = maturityLevel;
    }

    /** @return the event-level default focus factor override, or {@code null} if unset */
    public Double getFocusFactor() {
        return focusFactor;
    }

    /** @param focusFactor the event-level default focus factor override, in {@code [0, 1]} */
    public void setFocusFactor(final Double focusFactor) {
        this.focusFactor = focusFactor;
    }

    /** @return the safety margin applied to the recommended engagement, or {@code null} if unset */
    public Double getMargeSecurite() {
        return margeSecurite;
    }

    /** @param margeSecurite the safety margin, in {@code [0, 1]} */
    public void setMargeSecurite(final Double margeSecurite) {
        this.margeSecurite = margeSecurite;
    }

    /** @return the story points per net person-day, or {@code null} if unset */
    public Double getPointsPerDay() {
        return pointsPerDay;
    }

    /** @param pointsPerDay the story points per net person-day */
    public void setPointsPerDay(final Double pointsPerDay) {
        this.pointsPerDay = pointsPerDay;
    }

    /** @return the committed story points, or {@code null} if not yet planned */
    public Double getCommittedPoints() {
        return committedPoints;
    }

    /** @param committedPoints the committed story points */
    public void setCommittedPoints(final Double committedPoints) {
        this.committedPoints = committedPoints;
    }

    /** @return the completed story points, or {@code null} if not yet closed */
    public Double getCompletedPoints() {
        return completedPoints;
    }

    /** @param completedPoints the completed story points */
    public void setCompletedPoints(final Double completedPoints) {
        this.completedPoints = completedPoints;
    }

    /** @return the weekdays counted as working days, {@code 0} (Sunday) .. {@code 6} (Saturday) */
    public Integer[] getWorkingDays() {
        return workingDays;
    }

    /** @return free-form notes, or {@code null} if none */
    public String getNotes() {
        return notes;
    }

    /** @param notes free-form notes, or {@code null} to clear */
    public void setNotes(final String notes) {
        this.notes = notes;
    }

    /** @return the creation timestamp */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return the last update timestamp */
    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
