package fr.pivot.collaboratif.session.kpi;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Publishes {@link SessionKpiUpdatedEvent} for every {@link SessionKpiDefinition} whenever a
 * session mutation makes them worth recomputing (EN19.4). Called from {@code
 * ModuleSessionService#start}/{@code #end} — the two lifecycle transitions that change {@code
 * session.sessions_run} (a session only "runs" once it leaves {@code DRAFT}) and, at {@code end},
 * additionally {@code session.completion_rate}.
 *
 * <p><strong>Deliberately not wired into every activity-level write</strong> (POLL vote, WORDCLOUD
 * submission, …) — doing so would touch six unrelated activity services for a signal whose only
 * current consumer (a future push-side dashboard) is itself not built yet. This publisher only
 * covers the two mutation points EN19.4 actually needs to satisfy its own acceptance criterion
 * ("quand sa valeur est recalculée, {@code kpi.updated} est publié") — a documented, accepted
 * simplification, the same kind {@link SessionKpiRepository}'s Javadoc already makes for the
 * all-time (no period filter) aggregate.
 */
@Component
public class SessionKpiEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    /**
     * Creates the publisher with its required collaborator.
     *
     * @param eventPublisher Spring's in-process application event bus
     */
    public SessionKpiEventPublisher(final ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Publishes one {@link SessionKpiUpdatedEvent} per Session live KPI. Intended to be called
     * from inside a {@code @Transactional} session-mutation method, right after the mutation is
     * persisted, mirroring {@code fr.pivot.collaboratif.whiteboard.member.BoardMemberService}'s
     * placement.
     *
     * @param tenantId   the owning tenant's identifier
     * @param teamId     the session's owning team, or {@code null}
     * @param occurredAt when the underlying mutation was committed
     */
    public void publishRecalculation(final Long tenantId, final Long teamId, final Instant occurredAt) {
        for (SessionKpiDefinition definition : SessionKpiDefinition.values()) {
            eventPublisher.publishEvent(new SessionKpiUpdatedEvent(tenantId, teamId, definition.kpiKey(), occurredAt));
        }
    }
}
