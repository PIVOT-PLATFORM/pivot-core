package fr.pivot.collaboratif.meeting.kpi;

/**
 * Spring Data JPA interface projection for {@link MeetopsKpiRepository#aggregate}, one row per
 * (tenant, optional team) scope, backing all five {@link MeetopsKpiDefinition} values at once — a
 * single query computes every KPI together, mirroring {@code
 * fr.pivot.collaboratif.session.kpi.SessionKpiAggregate}'s own shape for EN19.4.
 */
public interface MeetopsKpiAggregate {

    /**
     * Returns the number of meetings actually held (reached {@code IN_PROGRESS} or {@code ENDED})
     * within the resolved scope.
     *
     * @return the {@code meetops.meetings_run} raw count
     */
    long getMeetingsRun();

    /**
     * Returns the share (0–100) of the team's members traceably engaged in its ended meetings.
     *
     * @return the {@code meetops.participation_rate} percentage, {@code 0} if no ended meeting or
     *     no team member
     */
    double getParticipationRate();

    /**
     * Returns the share (0–100) of captured in-meeting actions no longer {@code OPEN}.
     *
     * @return the {@code meetops.action_completion_rate} percentage, {@code 0} if no action was
     *     captured
     */
    double getActionCompletionRate();

    /**
     * Returns the average adherence (0–100) of actual agenda-item duration to its planned
     * duration.
     *
     * @return the {@code meetops.agenda_adherence} percentage, {@code 0} if no agenda item
     *     completed
     */
    double getAgendaAdherence();

    /**
     * Returns the share (0–100) of ended meetings whose compte-rendu was generated/shared.
     *
     * @return the {@code meetops.minutes_shared_rate} percentage, {@code 0} if no meeting ended
     */
    double getMinutesSharedRate();
}
