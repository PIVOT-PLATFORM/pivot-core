package fr.pivot.collaboratif.session.kpi;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The five Session live KPIs exposed by this domain (EN19.4), each declaring the metadata a
 * {@code KpiRef} consumer needs: {@code unit}, {@code supportedScopes}, {@code refreshHint} and
 * {@code visibility} (allowed roles), per the schema documented by {@code pivot-docs}' EN28.14
 * ("Contrat producteur de KPI").
 *
 * <p><strong>Scope of this enum, precisely.</strong> EN28.14 itself — the transverse, reusable
 * {@code KpiRef} contract meant to be implemented by every future PIVOT domain — has no code
 * anywhere in this repository yet (verified at the time this enabler, EN19.4, was implemented).
 * This enum does not attempt to build that shared abstraction; it only reproduces the {@code
 * KpiRef} *shape* for this one domain, so {@link SessionKpiController}/{@link SessionKpiService}
 * already speak the contract a future generic EN28.14 implementation (or a future OKR consumer,
 * US27.8.3) would expect, without itself becoming — or blocking on — that shared abstraction.
 *
 * <p><strong>Visibility.</strong> All five KPIs are team/tenant-level aggregates with no
 * participant-level breakdown (no name, no user id, no per-participant answer) — same RGPD
 * posture as {@code fr.pivot.agilite.capacity.kpi.KpiResponse}'s equivalent note — so none of
 * them is restricted today: both platform roles that can ever reach this endpoint ({@code
 * ROLE_ADMIN}, {@code ROLE_USER} — {@code ROLE_GUEST} participants never resolve a {@link
 * fr.pivot.collaboratif.context.CollaboratifRequestPrincipal} at all, see that record's Javadoc)
 * see every KPI. {@link #allowedRoles()} still exists and is enforced per-definition (not
 * hard-coded in the service) precisely so a future KPI with a real restriction need only change
 * the list here, and {@link SessionKpiService} correctly hides it from the list / 403s its pull
 * without any other code change.
 */
public enum SessionKpiDefinition {

    /** Number of sessions created and launched over the domain's lifetime (tenant or team). */
    SESSIONS_RUN("session.sessions_run", "count", List.of("tenant", "team")),

    /** Average number of participants (accounts + guests) per launched session (team only). */
    AVG_PARTICIPANTS("session.avg_participants", "participants/session", List.of("team")),

    /** Share of participants who interacted with at least one activity (team only). */
    PARTICIPATION_RATE("session.participation_rate", "%", List.of("team")),

    /** Number of activities (QUIZ/POLL/WORDCLOUD/…) executed (tenant or team). */
    ACTIVITIES_RUN("session.activities_run", "count", List.of("tenant", "team")),

    /** Share of launched sessions driven through to {@code COMPLETED} (team only). */
    COMPLETION_RATE("session.completion_rate", "%", List.of("team"));

    private static final List<String> ALLOWED_ROLES = List.of("ROLE_ADMIN", "ROLE_USER");

    /**
     * How often a consumer should poll this pull endpoint rather than treat a fetched value as
     * durably fresh. Every KPI here is computed on demand (no cached/persisted KPI row — see
     * {@link SessionKpiService}), so this is purely a consumer-facing hint, not a recompute
     * cadence this module itself schedules.
     */
    private static final Duration REFRESH_HINT = Duration.ofMinutes(1);

    private final String kpiKey;
    private final String unit;
    private final List<String> supportedScopes;

    SessionKpiDefinition(final String kpiKey, final String unit, final List<String> supportedScopes) {
        this.kpiKey = kpiKey;
        this.unit = unit;
        this.supportedScopes = supportedScopes;
    }

    /**
     * Returns the stable, domain-prefixed identifier of this KPI.
     *
     * @return the {@code kpiKey}, e.g. {@code "session.sessions_run"}
     */
    public String kpiKey() {
        return kpiKey;
    }

    /**
     * Returns the unit this KPI's value is expressed in.
     *
     * @return the unit, e.g. {@code "%"} or {@code "count"}
     */
    public String unit() {
        return unit;
    }

    /**
     * Returns the granularities this KPI can be resolved at.
     *
     * @return an immutable list containing {@code "tenant"} and/or {@code "team"}
     */
    public List<String> supportedScopes() {
        return List.copyOf(supportedScopes);
    }

    /**
     * Returns the roles allowed to list or resolve this KPI.
     *
     * @return an immutable list of Spring Security role names
     */
    public List<String> allowedRoles() {
        return ALLOWED_ROLES;
    }

    /**
     * Returns the consumer-facing polling hint.
     *
     * @return the refresh hint duration
     */
    public Duration refreshHint() {
        return REFRESH_HINT;
    }

    /**
     * Resolves a definition by its {@code kpiKey}.
     *
     * @param kpiKey the candidate key
     * @return the matching definition, or {@link Optional#empty()} if unknown
     */
    public static Optional<SessionKpiDefinition> byKey(final String kpiKey) {
        return Arrays.stream(values()).filter(definition -> definition.kpiKey.equals(kpiKey)).findFirst();
    }
}
