package fr.pivot.collaboratif.meeting.kpi;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Publishes {@link MeetopsKpiUpdatedEvent} for every {@link MeetopsKpiDefinition} whenever a
 * meeting mutation makes them worth recomputing (EN12.3). Mirrors {@code
 * fr.pivot.collaboratif.session.kpi.SessionKpiEventPublisher}'s own shape and placement
 * discipline for EN19.4.
 *
 * <p><strong>Wired from the two call sites that close a meeting</strong> — {@code
 * fr.pivot.collaboratif.meeting.MeetingAnimationService#end} and the last-agenda-item branch of
 * {@code #next} — right after {@code MeetingReportService#freezeOnClose} persists the frozen
 * compte-rendu snapshot in the same transaction. On this branch, "réunion clôturée" and "compte-
 * rendu partagé" (the enabler's first two trigger events) are literally the same commit — {@code
 * freezeOnClose} runs unconditionally and immediately at every {@code ENDED} transition, there is
 * no separate, later "share" action — so one {@link #publishRecalculation} call at that single
 * point already covers both.
 *
 * <p><strong>Deliberately NOT wired to the third trigger event, "action de réunion clôturée"
 * ("action closed")</strong> — as of this branch (through US12.3.1), no code path anywhere ever
 * transitions {@code fr.pivot.collaboratif.meeting.MeetingAction#getStatus} away from {@code
 * STATUS_OPEN}; that capability is explicitly deferred to a future US12.3.2 (see {@code
 * MeetingAction}'s own Javadoc). There is no mutation to hook this publisher into for that
 * trigger yet — wiring it in once US12.3.2 adds a real "close action" mutation is a one-line
 * addition at that call site, not a redesign of this class.
 */
@Component
public class MeetopsKpiEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    /**
     * Creates the publisher with its required collaborator.
     *
     * @param eventPublisher Spring's in-process application event bus
     */
    public MeetopsKpiEventPublisher(final ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Publishes one {@link MeetopsKpiUpdatedEvent} per MeetOps KPI. Intended to be called from
     * inside a {@code @Transactional} meeting-mutation method, right after the mutation is
     * persisted.
     *
     * @param tenantId   the owning tenant's identifier
     * @param teamId     the meeting's owning team, or {@code null}
     * @param occurredAt when the underlying mutation was committed
     */
    public void publishRecalculation(final Long tenantId, final Long teamId, final Instant occurredAt) {
        for (MeetopsKpiDefinition definition : MeetopsKpiDefinition.values()) {
            eventPublisher.publishEvent(
                    new MeetopsKpiUpdatedEvent(tenantId, teamId, definition.kpiKey(), occurredAt));
        }
    }
}
