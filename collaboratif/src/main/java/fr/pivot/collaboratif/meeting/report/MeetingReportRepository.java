package fr.pivot.collaboratif.meeting.report;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link MeetingReportSnapshot} persistence (US12.3.1).
 */
public interface MeetingReportRepository extends JpaRepository<MeetingReportSnapshot, Long> {

    /**
     * Finds the frozen snapshot for a meeting, if one has been generated at closure.
     *
     * @param meetingId the meeting's UUID
     * @return the snapshot, or {@link Optional#empty()} if the meeting has never been closed
     */
    Optional<MeetingReportSnapshot> findByMeetingId(UUID meetingId);
}
