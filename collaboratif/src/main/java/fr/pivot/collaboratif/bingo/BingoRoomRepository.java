package fr.pivot.collaboratif.bingo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link BingoRoom} (US47.1.1).
 */
public interface BingoRoomRepository extends JpaRepository<BingoRoom, UUID> {

    /**
     * Finds a room by its invite code.
     *
     * @param code the 6-character invite code
     * @return the matching room, if any
     */
    Optional<BingoRoom> findByCode(String code);

    /**
     * @param code the candidate invite code
     * @return {@code true} if a room already uses this code
     */
    boolean existsByCode(String code);

    /**
     * Atomically transitions a room from {@code OPEN} to {@code FINISHED} recording the winner,
     * guaranteeing a unique winner under concurrent completions (AC-47.1.1-10/11): the {@code
     * WHERE status = 'OPEN'} clause makes this a compare-and-swap — only the caller whose update
     * actually matches a row (return value {@code 1}) is the declared winner; every other
     * concurrent caller sees {@code 0} and must not broadcast a second {@code BINGO}.
     *
     * @param roomId              the room id
     * @param winnerParticipantId the winning {@link BingoGrid}'s id
     * @param winningLineKind     {@code ROW}/{@code COLUMN}/{@code DIAGONAL}
     * @param winningLineIndex    the winning line's index (0..4)
     * @return the number of rows updated — {@code 1} if this call won the race, {@code 0} otherwise
     */
    @Modifying
    @Query("UPDATE BingoRoom r SET r.status = 'FINISHED', r.winnerParticipantId = :winnerParticipantId, "
            + "r.winningLineKind = :winningLineKind, r.winningLineIndex = :winningLineIndex "
            + "WHERE r.id = :roomId AND r.status = 'OPEN'")
    int finishIfOpen(
            @Param("roomId") UUID roomId,
            @Param("winnerParticipantId") UUID winnerParticipantId,
            @Param("winningLineKind") String winningLineKind,
            @Param("winningLineIndex") int winningLineIndex);
}
