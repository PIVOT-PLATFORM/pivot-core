package fr.pivot.agilite.capacity.kpi.dto;

import java.util.Map;

/**
 * Response of {@code fr.pivot.agilite.capacity.kpi.KpiService#getTeamKpis} (EN11.2 — Capacity
 * KPI, Wave 2) — the five E11 capacity KPIs, aggregated at team level across every capacity event
 * of the resolved team/tenant.
 *
 * <p><strong>RGPD posture.</strong> Every value here is a team-level aggregate (sum/average/count
 * across the team's events and members) — no member name, id, or per-member breakdown is ever
 * included, unlike {@code fr.pivot.agilite.capacity.dto.CapacitySummaryResponse}, which this
 * endpoint deliberately does not reuse for that reason.
 *
 * @param teamId           the resolved team's identifier ({@code public.teams.id})
 * @param eventSampleSize  how many of the team's capacity events fed {@code
 *                         capacity.taux_utilisation}/{@code capacity.capacite_nette}/{@code
 *                         capacity.taux_absence}/{@code capacity.depassements}
 * @param sprintSampleSize how many closed-out ({@code agilite.capacity_velocity}-backed) sprints
 *                         fed {@code capacity.velocite_moyenne} — {@code 0} when the team has no
 *                         sprint-type event or none has been closed out yet
 * @param kpis             the five KPI values, keyed by their stable E11 identifier — see {@link
 *                         fr.pivot.agilite.capacity.kpi.KpiService} for the exact key constants
 *                         and computation of each one; {@code capacity.velocite_moyenne} is the
 *                         only key whose value may be {@code null} (no closed-out sprint yet)
 */
public record KpiResponse(
        Long teamId,
        int eventSampleSize,
        int sprintSampleSize,
        Map<String, Double> kpis) {
}
