package fr.pivot.collaboratif.meeting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for a decision recorded during a meeting, backed by the {@code
 * collaboratif.meeting_decisions} table posed additively in V18 (US12.2.1) alongside {@link
 * MeetingAction} but deliberately left without any writing code in that US — decision capture is
 * out of US12.2.1's scope (see its pivot-docs "Hors-périmètre" note). Shared fields ({@code id}/
 * {@code tenantId}/{@code meetingId}/{@code agendaItemId}/{@code label}) live in {@link
 * MeetingCaptureEntity}, shared with {@link MeetingAction}.
 *
 * <p>This entity is the first code in this module to read that table — introduced by US12.3.1
 * (the compte-rendu) purely as a read model: {@code MeetingReportService#buildReport} aggregates
 * whatever rows already exist for a meeting, empty or not, exactly the way {@link MeetingAction}
 * is aggregated. No capture endpoint is added here; a future US (decision capture UI, mirroring
 * US12.2.1 AC-08's action capture) is expected to populate this table — this US's report simply
 * never assumes it is non-empty.
 *
 * <p>Not part of the {@link Meeting} JPA aggregate, same reasoning as {@link MeetingAction}: a
 * plain {@code meetingId} scalar foreign key is enough, nothing in this module reads a meeting's
 * decisions back through its entity graph.
 */
@Entity
@Table(name = "meeting_decisions", schema = "collaboratif")
public class MeetingDecision extends MeetingCaptureEntity {

    /** Timestamp the decision was recorded. */
    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    /** Optional recording user's {@code public.users.id}, or {@code null}. */
    @Column(name = "created_by")
    private Long createdBy;

    /** No-arg constructor required by JPA. */
    protected MeetingDecision() {
    }

    /**
     * Full constructor. Public — unlike {@link #MeetingDecision()}, this is exercised today only
     * by this module's own tests (no capture endpoint exists yet, see this class's own Javadoc),
     * but is kept public rather than test-scoped so a future decision-capture service can reuse it
     * without a cross-cutting visibility change.
     *
     * @param tenantId     owning tenant's {@code public.tenants.id}
     * @param meetingId    the meeting this decision was recorded during
     * @param agendaItemId the agenda item current at recording time, or {@code null}
     * @param label        the decision's description
     * @param decidedAt    timestamp the decision was recorded
     * @param createdBy    optional recording user's {@code public.users.id}, or {@code null}
     */
    public MeetingDecision(
            final Long tenantId, final UUID meetingId, final UUID agendaItemId, final String label,
            final Instant decidedAt, final Long createdBy) {
        super(tenantId, meetingId, agendaItemId, label);
        this.decidedAt = decidedAt;
        this.createdBy = createdBy;
    }

    /**
     * Returns the timestamp the decision was recorded.
     *
     * @return the decidedAt instant
     */
    public Instant getDecidedAt() {
        return decidedAt;
    }

    /**
     * Returns the optional recording user's id.
     *
     * @return the recorder's {@code public.users.id}, or {@code null}
     */
    public Long getCreatedBy() {
        return createdBy;
    }
}
