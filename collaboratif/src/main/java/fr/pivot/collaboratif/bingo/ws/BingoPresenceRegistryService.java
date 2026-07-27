package fr.pivot.collaboratif.bingo.ws;

import fr.pivot.collaboratif.bingo.BingoParticipantRole;
import fr.pivot.collaboratif.bingo.BingoTokenHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks the live presence roster of a Bingo room — who is currently a {@code PLAYER} vs. a
 * {@code SPECTATOR} (US47.1.1, AC-47.1.1-06/13) — declines
 * {@code fr.pivot.agilite.poker.ws.PokerParticipantRegistryService}'s single-Redis-hash pattern
 * inside this module (no inter-module dependency is possible, ADR-006): backs the
 * {@code playerCount}/{@code spectatorCount} broadcast on {@code PARTICIPANT_JOINED}.
 *
 * <p>Backed by a single Redis hash per room ({@code bingo:room-roster:{roomId}}): field = the hex
 * SHA-256 digest of the participant's accessToken (never the raw token), value = the {@link
 * BingoParticipantRole} name. The hash's whole-key TTL is refreshed on every {@link #register}
 * call; Redis hashes have no per-field expiry — acceptable here since every registration in a
 * room happens against the same room-wide {@code ttl} window (the room's own {@code expiresAt}).
 */
@Service
public class BingoPresenceRegistryService {

    private static final Logger LOG = LoggerFactory.getLogger(BingoPresenceRegistryService.class);

    /** Redis key prefix for a room's presence roster hash. */
    private static final String ROSTER_KEY_PREFIX = "bingo:room-roster:";

    private final StringRedisTemplate redisTemplate;

    /**
     * Creates the service with the shared Redis client.
     *
     * @param redisTemplate Redis client used to store the roster hash
     */
    public BingoPresenceRegistryService(final StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Registers (or updates) a participant in a room's presence roster, refreshing the roster's
     * overall TTL to {@code ttl} from now. Idempotent by hashed access token — re-registering the
     * same token overwrites the entry rather than duplicating it.
     *
     * @param roomId      the room's identifier
     * @param accessToken the participant's opaque access token
     * @param role        the participant's role
     * @param ttl         how long the roster remains valid
     */
    public void register(final UUID roomId, final String accessToken, final BingoParticipantRole role, final Duration ttl) {
        String key = rosterKey(roomId);
        redisTemplate.opsForHash().put(key, BingoTokenHasher.hash(accessToken), role.name());
        redisTemplate.expire(key, ttl);
        LOG.info("Bingo room participant registered: room={} role={} ttlSeconds={}", roomId, role, ttl.toSeconds());
    }

    /**
     * Counts the currently registered {@code PLAYER} participants of a room.
     *
     * @param roomId the room's identifier
     * @return the player count, or {@code 0} if the roster is empty/expired
     */
    public int countPlayers(final UUID roomId) {
        return countByRole(roomId, BingoParticipantRole.PLAYER);
    }

    /**
     * Counts the currently registered {@code SPECTATOR} participants of a room.
     *
     * @param roomId the room's identifier
     * @return the spectator count, or {@code 0} if the roster is empty/expired
     */
    public int countSpectators(final UUID roomId) {
        return countByRole(roomId, BingoParticipantRole.SPECTATOR);
    }

    private int countByRole(final UUID roomId, final BingoParticipantRole role) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(rosterKey(roomId));
        int count = 0;
        for (Object value : entries.values()) {
            if (role.name().equals(value)) {
                count++;
            }
        }
        return count;
    }

    private String rosterKey(final UUID roomId) {
        return ROSTER_KEY_PREFIX + roomId;
    }
}
