package fr.pivot.agilite.capacity.rgpd;

import java.util.List;

/**
 * Response payload for the data-subject access/portability export (US11.8.1, RGPD Art. 15/20) —
 * {@code GET /agilite/capacity/rgpd/members/{teamMemberRef}/data}.
 *
 * <p>Deliberately scoped to a single person's own absence periods, never aggregated/anonymised —
 * the aggregation-by-default principle (never a nominative breakdown in payloads/KPI/cards, see
 * {@code docs/rgpd/capacity-registre.md}) governs the module's regular read endpoints, not this
 * one, whose entire purpose is to hand that same person their own data back.
 *
 * @param teamMemberRef the requested {@code public.team_members.id}
 * @param absences      every capacity absence recorded for that person, across the caller's
 *                      tenant/teams
 */
public record CapacityRgpdExportResponse(
        Long teamMemberRef,
        List<CapacityRgpdAbsenceResponse> absences) {
}
