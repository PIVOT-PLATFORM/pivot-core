package fr.pivot.collaboratif.bingo.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Issues and checks room access grants — the sole mechanism by which a STOMP client is authorized
 * to subscribe to, or send into, a Bingo room (US47.1.1, SEC-01) — declines
 * {@code fr.pivot.agilite.poker.ws.RoomAccessGrantService}'s exact contract inside this module (no
 * inter-module dependency is possible, ADR-006): {@link #grantAccess} mints a room-scoped grant on
 * a successful create/join; {@link BingoChannelInterceptor} is the sole consumer of {@link
 * #hasAccess}.
 *
 * <p><strong>Tenant isolation by construction:</strong> the grant is keyed by {@code (roomId,
 * accessToken)} only. {@code accessToken} is an opaque, unguessable {@code UUID.randomUUID()}
 * minted by the create/join flow (SEC-03) — never a client-supplied identifier. There is no
 * parameter here for a malicious client to spoof; identity is entirely determined by which grant,
 * if any, the presented token happens to unlock.
 *
 * <p>Grants expire automatically via the Redis key TTL passed to {@link #grantAccess}, aligned by
 * the caller with the room's own {@code expiresAt} — no explicit revocation API is needed for the
 * room-isolation guarantee itself.
 */
@Service
public class BingoRoomAccessGrantService {

    private static final Logger LOG = LoggerFactory.getLogger(BingoRoomAccessGrantService.class);

    /** Redis key prefix for room access grants. */
    private static final String GRANT_KEY_PREFIX = "bingo:room-access:";

    /** Value stored for a granted key — presence of the key is what matters, not its content. */
    private static final String GRANTED_VALUE = "1";

    private final StringRedisTemplate redisTemplate;

    /**
     * Creates the service with the shared Redis client.
     *
     * @param redisTemplate Redis client used to store and check grants
     */
    public BingoRoomAccessGrantService(final StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Grants a participant access to a room for the given duration. Idempotent: granting again
     * for the same {@code (roomId, accessToken)} simply refreshes the TTL to {@code ttl} from now.
     *
     * @param roomId      the room's identifier
     * @param accessToken the opaque access token minted by the caller for this participant
     * @param ttl         how long the grant remains valid
     */
    public void grantAccess(final UUID roomId, final String accessToken, final Duration ttl) {
        String key = grantKey(roomId, accessToken);
        redisTemplate.opsForValue().set(key, GRANTED_VALUE, ttl);
        LOG.info("Bingo room access granted: room={} ttlSeconds={}", roomId, ttl.toSeconds());
    }

    /**
     * Checks whether a currently valid grant exists for the given room and access token.
     *
     * @param roomId      the room's identifier
     * @param accessToken the access token presented by the client, or {@code null}/blank if none
     * @return {@code true} if a non-expired grant exists for this exact {@code (roomId,
     *     accessToken)} pair
     */
    public boolean hasAccess(final UUID roomId, final String accessToken) {
        if (roomId == null || accessToken == null || accessToken.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(grantKey(roomId, accessToken)));
    }

    private String grantKey(final UUID roomId, final String accessToken) {
        return GRANT_KEY_PREFIX + roomId + ":" + accessToken;
    }
}
