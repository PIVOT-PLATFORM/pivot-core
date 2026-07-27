package fr.pivot.collaboratif.meeting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA entity for a minimal in-meeting action capture (US12.2.1 AC-08) — {@code label} +
 * {@code owner_user_id} + {@code due_date}, deliberately not the fuller action model
 * (assignment workflow, status transitions beyond {@link #status}) US12.3.1/US12.3.2 own; see
 * this US's pivot-docs "Hors-périmètre" note. Shared fields ({@code id}/{@code tenantId}/
 * {@code meetingId}/{@code agendaItemId}/{@code label}) live in {@link MeetingCaptureEntity},
 * shared with {@link MeetingDecision}.
 *
 * <p>Not part of the {@link Meeting} JPA aggregate (unlike {@link AgendaItem}) — actions are
 * captured incrementally throughout a live meeting via their own {@code POST .../actions}
 * endpoint, not created/cascaded alongside the meeting itself, so a plain {@code meetingId}
 * scalar foreign key (rather than a {@code @ManyToOne}) is enough; nothing in this module reads a
 * meeting's actions back through its entity graph today.
 */
@Entity
@Table(name = "meeting_actions", schema = "collaboratif")
public class MeetingAction extends MeetingCaptureEntity {

    /** Default status every action is created in — no workflow beyond capture in this US. */
    public static final String STATUS_OPEN = "OPEN";

    /** Optional owning user's {@code public.users.id}, or {@code null} if unassigned. */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    /** Optional due date; never strictly before today (enforced at the API layer). */
    @Column(name = "due_date")
    private LocalDate dueDate;

    /** Workflow status; always {@link #STATUS_OPEN} at creation in this US. */
    @Column(nullable = false, length = 20)
    private String status = STATUS_OPEN;

    /** Timestamp when this action was captured. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** No-arg constructor required by JPA. */
    protected MeetingAction() {
    }

    /**
     * Full constructor for creating a new captured action (US12.2.1 AC-08).
     *
     * @param tenantId     owning tenant's {@code public.tenants.id} — from {@code TenantContext},
     *                     never client-supplied
     * @param meetingId    the meeting this action was captured during
     * @param agendaItemId the agenda item current at capture time, or {@code null}
     * @param label        the action's description (never blank)
     * @param ownerUserId  optional owning user's {@code public.users.id}, or {@code null}
     * @param dueDate      optional due date, or {@code null}
     * @param now          timestamp used for {@code createdAt}
     */
    public MeetingAction(
            final Long tenantId, final UUID meetingId, final UUID agendaItemId, final String label,
            final Long ownerUserId, final LocalDate dueDate, final Instant now) {
        super(tenantId, meetingId, agendaItemId, label);
        this.ownerUserId = ownerUserId;
        this.dueDate = dueDate;
        this.status = STATUS_OPEN;
        this.createdAt = now;
    }

    /**
     * Returns the optional owning user's id.
     *
     * @return the owner's {@code public.users.id}, or {@code null}
     */
    public Long getOwnerUserId() {
        return ownerUserId;
    }

    /**
     * Returns the optional due date.
     *
     * @return the due date, or {@code null}
     */
    public LocalDate getDueDate() {
        return dueDate;
    }

    /**
     * Returns the workflow status.
     *
     * @return the status
     */
    public String getStatus() {
        return status;
    }

    /**
     * Returns the capture timestamp.
     *
     * @return the createdAt instant
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
