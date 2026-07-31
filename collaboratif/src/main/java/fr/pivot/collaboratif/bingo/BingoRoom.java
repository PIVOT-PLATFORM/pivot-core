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
 * JPA entity backing a Bingo des reunions room (US47.1.1), table {@code collaboratif.bingo_rooms}.
 *
 * <p>Never exposed directly over the API — {@link BingoRoomController} always returns a {@code
 * BingoRoomResponse} DTO built by {@link BingoRoomService}, per this repo's "no JPA entity in API
 * responses" standard.
 *
 * <p>{@code winnerParticipantId} is the winning {@link BingoGrid}'s own id (never a raw
 * accessToken or its hash) — see {@link BingoGrid}'s Javadoc for why the grid id is the
 * non-enumerable identifier broadcast as {@code participantId} on the wire.
 */
@Entity
@Table(name = "bingo_rooms", schema = "collaboratif")
public class BingoRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 6)
    private String code;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "creator_user_id")
    private Long creatorUserId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "max_players", nullable = false)
    private int maxPlayers;

    @Column(name = "winner_participant_id")
    private UUID winnerParticipantId;

    @Column(name = "winning_line_kind", length = 20)
    private String winningLineKind;

    @Column(name = "winning_line_index")
    private Integer winningLineIndex;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** No-argument constructor required by JPA. */
    protected BingoRoom() {
    }

    /**
     * Creates a new room ready to persist. Status always starts {@code OPEN}.
     *
     * @param code          the generated, pre-checked-unique invite code
     * @param name          the room's display name
     * @param creatorUserId the creator's {@code public.users.id}, or {@code null} if unresolved
     * @param tenantId      the creator's tenant id, or {@code null} if unresolved
     * @param maxPlayers    the configured player threshold before spectator degradation
     * @param createdAt     creation timestamp
     * @param expiresAt     expiry timestamp
     */
    public BingoRoom(
            final String code,
            final String name,
            final Long creatorUserId,
            final Long tenantId,
            final int maxPlayers,
            final Instant createdAt,
            final Instant expiresAt) {
        this.code = code;
        this.name = name;
        this.creatorUserId = creatorUserId;
        this.tenantId = tenantId;
        this.maxPlayers = maxPlayers;
        this.status = BingoRoomStatus.OPEN.name();
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** @return database primary key */
    public UUID getId() {
        return id;
    }

    /** @return the 6-character invite code */
    public String getCode() {
        return code;
    }

    /** @return the room's display name */
    public String getName() {
        return name;
    }

    /** @return the creator's {@code public.users.id}, or {@code null} */
    public Long getCreatorUserId() {
        return creatorUserId;
    }

    /** @return the creator's tenant id, or {@code null} */
    public Long getTenantId() {
        return tenantId;
    }

    /** @return the current lifecycle status ({@code OPEN} or {@code FINISHED}) */
    public BingoRoomStatus getStatus() {
        return BingoRoomStatus.valueOf(status);
    }

    /** @return the configured maximum simultaneous players before spectator degradation */
    public int getMaxPlayers() {
        return maxPlayers;
    }

    /** @return the winning {@link BingoGrid}'s id, or {@code null} while the room is still OPEN */
    public UUID getWinnerParticipantId() {
        return winnerParticipantId;
    }

    /** @return the winning line's kind ({@code ROW}/{@code COLUMN}/{@code DIAGONAL}), or {@code null} */
    public String getWinningLineKind() {
        return winningLineKind;
    }

    /** @return the winning line's index (0..4), or {@code null} */
    public Integer getWinningLineIndex() {
        return winningLineIndex;
    }

    /** @return the creation timestamp */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return the expiry timestamp */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /**
     * @param now the current instant
     * @return {@code true} if the room is still {@code OPEN} and not past {@link #expiresAt}
     */
    public boolean isJoinable(final Instant now) {
        return getStatus() == BingoRoomStatus.OPEN && expiresAt.isAfter(now);
    }
}
