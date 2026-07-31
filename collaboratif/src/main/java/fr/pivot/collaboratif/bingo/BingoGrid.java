package fr.pivot.collaboratif.bingo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity backing a single participant's private grid within a Bingo room (US47.1.1), table
 * {@code collaboratif.bingo_grids} — one row per {@code (room, participant)} pair, PLAYER role
 * only (SPECTATOR admission never creates a row here, see {@link BingoParticipantRole}).
 *
 * <p><strong>This row's own {@code id} is the {@code participantId} broadcast on the wire</strong>
 * ({@code PARTICIPANT_JOINED}, {@code CELL_MARKED}, {@code BINGO} — AC-47.1.1-06/07/10). It is
 * deliberately used instead of the raw accessToken or its SHA-256 hash: an opaque, room-scoped,
 * randomly-generated {@code UUID} that reveals nothing about the participant's identity or grant
 * (SEC-02/SEC-03), yet stays stable for the room's lifetime so the frontend's "who has marked how
 * many cells" progress table can key its rows by it.
 *
 * <p>{@code participantKey} is the hex SHA-256 digest of the participant's opaque accessToken
 * (SEC-03) — the raw token is never persisted anywhere. Every mark/grid lookup resolves the
 * caller's own grid exclusively via {@code (roomId, participantKey)} derived from the grant
 * presented on the current request/frame — never from a client-supplied identifier (SEC-02).
 */
@Entity
@Table(name = "bingo_grids", schema = "collaboratif")
public class BingoGrid {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "participant_key", nullable = false, length = 64)
    private String participantKey;

    @Column(name = "display_name", nullable = false, length = 30)
    private String displayName;

    @Column(name = "role", nullable = false, length = 20)
    private String role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** No-argument constructor required by JPA. */
    protected BingoGrid() {
    }

    /**
     * Creates a new player grid record ready to persist.
     *
     * @param roomId         the owning room's id
     * @param participantKey the hex SHA-256 digest of the participant's opaque accessToken
     * @param displayName    the resolved, already-validated/sanitized display name (SEC-05)
     * @param createdAt      creation timestamp
     */
    public BingoGrid(final UUID roomId, final String participantKey, final String displayName, final Instant createdAt) {
        this.roomId = roomId;
        this.participantKey = participantKey;
        this.displayName = displayName;
        this.role = BingoParticipantRole.PLAYER.name();
        this.createdAt = createdAt;
    }

    /** @return database primary key — also this participant's wire-level {@code participantId} */
    public UUID getId() {
        return id;
    }

    /** @return the owning room's id */
    public UUID getRoomId() {
        return roomId;
    }

    /** @return the hex SHA-256 digest of the participant's opaque accessToken */
    public String getParticipantKey() {
        return participantKey;
    }

    /** @return the resolved display name */
    public String getDisplayName() {
        return displayName;
    }

    /** @return the participant's role — always {@code PLAYER} for a persisted grid */
    public BingoParticipantRole getRole() {
        return BingoParticipantRole.valueOf(role);
    }

    /** @return the creation timestamp */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
