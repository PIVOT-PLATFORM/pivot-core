package fr.pivot.collaboratif.session.kpi;

/**
 * Spring Data JPA interface projection for {@link SessionKpiRepository#aggregate}, one row per
 * (tenant, optional team) scope, backing all five {@link SessionKpiDefinition} values at once —
 * a single query computes every KPI together since they share the same underlying session
 * population, rather than one query per KPI.
 */
public interface SessionKpiAggregate {

    /**
     * Returns the number of sessions that left {@code DRAFT} within the resolved scope.
     *
     * @return the {@code session.sessions_run} raw count
     */
    long getSessionsRun();

    /**
     * Returns the number of activities belonging to a launched session within the resolved
     * scope.
     *
     * @return the {@code session.activities_run} raw count
     */
    long getActivitiesRun();

    /**
     * Returns the average number of participants per launched session within the resolved
     * scope.
     *
     * @return the {@code session.avg_participants} value, {@code 0} if no session was launched
     */
    double getAvgParticipants();

    /**
     * Returns the share (0–100) of participants who submitted at least one activity interaction.
     *
     * @return the {@code session.participation_rate} percentage, {@code 0} if no participant
     */
    double getParticipationRate();

    /**
     * Returns the share (0–100) of launched sessions that reached {@code COMPLETED}.
     *
     * @return the {@code session.completion_rate} percentage, {@code 0} if no session was
     *     launched
     */
    double getCompletionRate();
}
