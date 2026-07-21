package fr.pivot.agilite.capacity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * JPA entity backing one member's participation in a {@link CapacityEvent} (E11 — capacity
 * planning), table {@code agilite.capacity_event_member}.
 *
 * <p>Ported from the PouetPouet POC's {@code CapacityEventMember} Prisma model, adapted to this
 * repo's conventions. {@code name}/{@code role} are a roster <em>snapshot</em>, copied from
 * {@code public.team_members} at add-time rather than re-derived by joining — same rationale as
 * {@code agilite.wheel_draw.entryLabel} — so an event's history stays stable even after the
 * underlying team roster changes; {@link #teamMemberRef} is kept only as a best-effort link back,
 * nullable and {@code ON DELETE SET NULL}.
 */
@Entity
@Table(name = "capacity_event_member", schema = "agilite")
public class CapacityEventMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    /** Best-effort link to {@code public.team_members.id} — nullable, survives its removal. */
    @Column(name = "team_member_ref")
    private Long teamMemberRef;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "role", length = 60)
    private String role;

    /** Full-time-equivalent quotity, {@code 1} = full-time, {@code 0.5} = half-time. */
    @Column(name = "quotite", nullable = false)
    private double quotite;

    /** Per-member focus factor override, in {@code [0, 1]} — takes precedence over role/event. */
    @Column(name = "focus_factor")
    private Double focusFactor;

    @Column(name = "locality", length = 60)
    private String locality;

    @Column(name = "excluded", nullable = false)
    private boolean excluded;

    /** Display order within the event — column named {@code position}, not {@code order}
     * (reserved word), same naming choice already made for {@code
     * agilite.retro_format_columns.position}. */
    @Column(name = "position", nullable = false)
    private int position;

    /** No-argument constructor required by JPA. */
    protected CapacityEventMember() {
    }

    /**
     * Creates a new event member ready to persist. {@code excluded} always starts {@code false}.
     *
     * @param eventId       the owning event's identifier
     * @param teamMemberRef the linked {@code public.team_members.id}, or {@code null} for a
     *                      free-text (non-roster) member
     * @param name          the member's display name (roster snapshot)
     * @param role          the member's role (roster snapshot), or {@code null}
     * @param quotite       the full-time-equivalent quotity, {@code 1} = full-time
     * @param position      the display order within the event
     */
    public CapacityEventMember(
            final UUID eventId,
            final Long teamMemberRef,
            final String name,
            final String role,
            final double quotite,
            final int position) {
        this.eventId = eventId;
        this.teamMemberRef = teamMemberRef;
        this.name = name;
        this.role = role;
        this.quotite = quotite;
        this.position = position;
        this.excluded = false;
    }

    /** @return database primary key */
    public UUID getId() {
        return id;
    }

    /** @return the owning event's identifier */
    public UUID getEventId() {
        return eventId;
    }

    /** @return the linked {@code public.team_members.id}, or {@code null} */
    public Long getTeamMemberRef() {
        return teamMemberRef;
    }

    /** @return the member's display name (roster snapshot) */
    public String getName() {
        return name;
    }

    /** @param name the member's display name */
    public void setName(final String name) {
        this.name = name;
    }

    /** @return the member's role (roster snapshot), or {@code null} */
    public String getRole() {
        return role;
    }

    /** @param role the member's role, or {@code null} to clear */
    public void setRole(final String role) {
        this.role = role;
    }

    /** @return the full-time-equivalent quotity, {@code 1} = full-time */
    public double getQuotite() {
        return quotite;
    }

    /** @param quotite the full-time-equivalent quotity */
    public void setQuotite(final double quotite) {
        this.quotite = quotite;
    }

    /** @return the per-member focus factor override, or {@code null} if unset */
    public Double getFocusFactor() {
        return focusFactor;
    }

    /** @param focusFactor the per-member focus factor override, in {@code [0, 1]} */
    public void setFocusFactor(final Double focusFactor) {
        this.focusFactor = focusFactor;
    }

    /** @return the member's locality, or {@code null} if unset */
    public String getLocality() {
        return locality;
    }

    /** @param locality the member's locality, or {@code null} to clear */
    public void setLocality(final String locality) {
        this.locality = locality;
    }

    /** @return {@code true} if this member is excluded from the event's capacity computation */
    public boolean isExcluded() {
        return excluded;
    }

    /** @param excluded whether to exclude this member from the event's capacity computation */
    public void setExcluded(final boolean excluded) {
        this.excluded = excluded;
    }

    /** @return the display order within the event */
    public int getPosition() {
        return position;
    }

    /** @param position the display order within the event */
    public void setPosition(final int position) {
        this.position = position;
    }
}
