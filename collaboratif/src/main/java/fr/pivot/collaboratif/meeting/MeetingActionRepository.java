package fr.pivot.collaboratif.meeting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data repository for {@link MeetingAction} persistence (US12.2.1 AC-08).
 */
public interface MeetingActionRepository extends JpaRepository<MeetingAction, UUID> {

    /**
     * Returns every action captured during a meeting, oldest first — used by {@code
     * MeetingReportService} (US12.3.1) to build both the live draft and the frozen snapshot's
     * {@code actions} section. Deliberately scoped by {@code meetingId} only (never a bare {@code
     * findAll}) so this report can never aggregate another meeting's actions (AC Security — no
     * cross-meeting leakage).
     *
     * @param meetingId the meeting's UUID
     * @return the matching actions, ordered by {@code createdAt} ascending
     */
    List<MeetingAction> findByMeetingIdOrderByCreatedAtAsc(UUID meetingId);
}
