package fr.pivot.collaboratif.meeting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data repository for {@link MeetingDecision} persistence (US12.3.1 — read-only in this
 * US, see that entity's own Javadoc).
 */
public interface MeetingDecisionRepository extends JpaRepository<MeetingDecision, UUID> {

    /**
     * Returns every decision recorded during a meeting, oldest first — used by {@code
     * MeetingReportService} to build both the live draft and the frozen snapshot's {@code
     * decisions} section. Deliberately scoped by {@code meetingId} only (never a bare {@code
     * findAll}) so this report can never aggregate another meeting's decisions (AC Security —
     * no cross-meeting leakage).
     *
     * @param meetingId the meeting's UUID
     * @return the matching decisions, ordered by {@code decidedAt} ascending
     */
    List<MeetingDecision> findByMeetingIdOrderByDecidedAtAsc(UUID meetingId);
}
