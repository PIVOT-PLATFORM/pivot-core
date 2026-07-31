package fr.pivot.collaboratif.bingo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link BingoGrid} (US47.1.1).
 */
public interface BingoGridRepository extends JpaRepository<BingoGrid, UUID> {

    /**
     * Resolves the calling participant's own grid — the sole lookup path used to authorize a
     * mark or a grid re-fetch (SEC-02): identity always comes from {@code (roomId,
     * participantKey)} derived from the presented grant, never from a client-supplied id.
     *
     * @param roomId         the room id
     * @param participantKey the hex SHA-256 digest of the presented accessToken
     * @return the participant's grid, or empty if this pair has no grid (unknown participant, or
     *     a spectator who never had one)
     */
    Optional<BingoGrid> findByRoomIdAndParticipantKey(UUID roomId, String participantKey);
}
