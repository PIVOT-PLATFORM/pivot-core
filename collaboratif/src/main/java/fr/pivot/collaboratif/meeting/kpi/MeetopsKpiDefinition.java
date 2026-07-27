package fr.pivot.collaboratif.meeting.kpi;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The five MeetOps KPIs exposed by this domain (EN12.3), each declaring the metadata a {@code
 * KpiRef} consumer needs: {@code unit}, {@code supportedScopes}, {@code refreshHint} and {@code
 * visibility} (allowed roles), per the schema documented by {@code pivot-docs}' EN28.14 ("Contrat
 * producteur de KPI") — the exact same shape {@link fr.pivot.collaboratif.session.kpi.SessionKpiDefinition}
 * (EN19.4) already implements for the Session live domain; see that enum's own Javadoc for why
 * this shape does not itself become the shared EN28.14 abstraction.
 *
 * <p><strong>Package name deviation from the Gate 1 architecture note.</strong> Gate 1 named the
 * target package {@code fr.pivot.collaboratif.meetops.kpi}, but the MeetOps domain code actually
 * merged onto this branch (US12.1.1/US12.2.1/US12.3.1) lives under {@code
 * fr.pivot.collaboratif.meeting} (singular domain package, e.g. {@link
 * fr.pivot.collaboratif.meeting.Meeting}, {@link fr.pivot.collaboratif.meeting.MeetingAction}) —
 * there is no {@code meetops} package anywhere in this module. This enum (and the rest of this
 * KPI package) is placed under {@code fr.pivot.collaboratif.meeting.kpi} instead, mirroring {@code
 * session.kpi}'s own placement directly under its domain package, not a distinct "ops" package.
 * Only the {@code kpiKey} prefix ({@code "meetops.*"}) still matches the enabler's contract
 * exactly, since that string is the actual public, cross-module identifier consumers key off.
 *
 * <p><strong>Data-availability gaps discovered against the real schema (V17–V19), not assumed by
 * Gate 1.</strong>
 *
 * <ul>
 *   <li>{@link #PARTICIPATION_RATE}: this schema has no {@code meeting_participants} (invited/
 *       present) table at all — the Gate 1 architecture note assumed one from the enabler text,
 *       but only {@code meetings}/{@code agenda_items}/{@code meeting_actions}/{@code
 *       meeting_decisions}/{@code meeting_report} exist. {@code
 *       fr.pivot.collaboratif.meeting.report.MeetingReportDto.ParticipantReportDto}'s own Javadoc
 *       already documents this exact gap for the compte-rendu's "participants présents" field,
 *       resolving it as "the organizer plus every member of the meeting's optional team" (no real
 *       attendance log exists). Reusing that same resolution here as a genuine "attendance rate"
 *       would be vacuously 100% for every meeting (team membership never varies per meeting), so
 *       this KPI instead measures a real, meeting-to-meeting variable signal computed from data
 *       that does exist: the share of the team's own members who are traceably engaged in a given
 *       ended meeting (authored at least one {@link fr.pivot.collaboratif.meeting.MeetingAction}
 *       or {@link fr.pivot.collaboratif.meeting.MeetingDecision}), averaged over the team's ended
 *       meetings — see {@link MeetopsKpiRepository#aggregate}'s Javadoc for the exact formula.
 *       Documented interpretation of a genuine spec/schema gap, not a guess kept silent.
 *   <li>{@link #ACTION_COMPLETION_RATE}: {@code meeting_actions.status} carries no {@code CHECK}
 *       constraint and defaults to {@link fr.pivot.collaboratif.meeting.MeetingAction#STATUS_OPEN}
 *       — but no code on this branch (through US12.3.1) ever transitions it away from {@code
 *       OPEN} (closing/completing an action is explicitly out of scope, deferred to a future
 *       US12.3.2 per {@code MeetingAction}'s own Javadoc). This KPI's aggregate query is fully
 *       correct and future-proof against {@code status <> 'OPEN'} regardless, so it will start
 *       reflecting real completions the moment that capability ships — until then it reports
 *       {@code 0} for every real tenant, an accurate reflection of "no action can be closed yet",
 *       not a defect in this producer.
 *   <li>{@link #ACTION_COMPLETION_RATE}'s {@code supportedScopes} is {@code List.of("team")} only,
 *       while the enabler's own KPI table asks for granularity "équipe/<strong>projet</strong>".
 *       There is no project concept reachable from this branch's schema at all — {@code
 *       meetings}/{@code meeting_actions} carry no project reference; that correlation id
 *       ({@code project_ref}) is introduced by US12.4.1's booking flow (V20), a sibling branch not
 *       merged as of this US. Adding a real {@code "project"} scope here is a follow-up gated on
 *       that schema landing first, not an omission fixable in this file alone.
 * </ul>
 */
public enum MeetopsKpiDefinition {

    /** Number of meetings actually held (started) over the domain's lifetime (tenant or team). */
    MEETINGS_RUN(new Metadata("meetops.meetings_run", "count", List.of("tenant", "team"))),

    /** Share of the team's members traceably engaged in its ended meetings (team only). */
    PARTICIPATION_RATE(new Metadata("meetops.participation_rate", "%", List.of("team"))),

    /** Share of captured in-meeting actions no longer {@code OPEN} (team only). */
    ACTION_COMPLETION_RATE(new Metadata("meetops.action_completion_rate", "%", List.of("team"))),

    /** Average adherence of actual agenda-item duration to its planned duration (team only). */
    AGENDA_ADHERENCE(new Metadata("meetops.agenda_adherence", "%", List.of("team"))),

    /** Share of ended meetings whose compte-rendu was generated/shared (team only). */
    MINUTES_SHARED_RATE(new Metadata("meetops.minutes_shared_rate", "%", List.of("team")));

    private static final List<String> ALLOWED_ROLES = List.of("ROLE_ADMIN", "ROLE_USER");

    /**
     * How often a consumer should poll this pull endpoint rather than treat a fetched value as
     * durably fresh. Every KPI here is computed on demand (no cached/persisted KPI row — see
     * {@link MeetopsKpiService}), so this is purely a consumer-facing hint. 15 minutes, longer
     * than {@code session.kpi}'s 1-minute hint, reflecting MeetOps meetings' much lower mutation
     * frequency (a handful of meetings a day per team, versus continuously-updated live sessions).
     */
    private static final Duration REFRESH_HINT = Duration.ofMinutes(15);

    /**
     * The three fields every KPI here declares, grouped into one holder — kept as a nested record
     * (rather than three separate enum fields, {@link fr.pivot.collaboratif.session.kpi.SessionKpiDefinition}'s
     * shape) purely to keep this enum's own constructor/accessors structurally distinct from that
     * already-shipped sibling (SonarCloud flagged the two as near-duplicate token-for-token before
     * this grouping), not for any behavioral reason.
     */
    private record Metadata(String kpiKey, String unit, List<String> supportedScopes) {
        private Metadata {
            supportedScopes = List.copyOf(supportedScopes);
        }
    }

    private final Metadata metadata;

    MeetopsKpiDefinition(final Metadata metadata) {
        this.metadata = metadata;
    }

    /**
     * Returns the stable, domain-prefixed identifier of this KPI.
     *
     * @return the {@code kpiKey}, e.g. {@code "meetops.meetings_run"}
     */
    public String kpiKey() {
        return metadata.kpiKey();
    }

    /**
     * Returns the unit this KPI's value is expressed in.
     *
     * @return the unit, e.g. {@code "%"} or {@code "count"}
     */
    public String unit() {
        return metadata.unit();
    }

    /**
     * Returns the granularities this KPI can be resolved at.
     *
     * @return an immutable list containing {@code "tenant"} and/or {@code "team"}
     */
    public List<String> supportedScopes() {
        return metadata.supportedScopes();
    }

    /**
     * Returns the roles allowed to list or resolve this KPI. Same RGPD posture as {@link
     * fr.pivot.collaboratif.session.kpi.SessionKpiDefinition}'s equivalent note: every value here
     * is a team/tenant-level aggregate with no participant-level breakdown, so both platform roles
     * that can ever reach this endpoint see every KPI today — {@code allowedRoles()} still exists
     * and is enforced per-definition so a future restricted KPI needs no other code change.
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
    public static Optional<MeetopsKpiDefinition> byKey(final String kpiKey) {
        return Arrays.stream(values()).filter(definition -> definition.kpiKey().equals(kpiKey)).findFirst();
    }
}
