package fr.pivot.agilite.poker.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks the live named roster of a planning poker room (E09 — classic parity): who is present,
 * their chosen display name, and their {@link ParticipantRole}. Backs both the "X/Y have voted"
 * denominator (via {@link #countActive}) and the named participant table broadcast to every
 * subscriber (via {@link #roster}).
 *
 * <p><strong>Not a WebSocket-connection presence tracker.</strong> Membership is keyed on the
 * participant's {@link PokerParticipantKey} (a hash of their room access token), not on a
 * currently-open STOMP session: a participant who closes their tab stays listed until their
 * access grant's window (the room's own expiry, US09.1.1) elapses — real-time disconnect
 * detection is deliberately deferred (US09.2.1 backlog "Hors périmètre").
 *
 * <p>Backed by a single Redis <em>hash</em> per room ({@code poker:room-roster:{roomId}}): field =
 * {@link PokerParticipantKey} (never the raw token), value = {@code "ROLE:name"} (the role's enum
 * name, then a colon, then the display name — the role has no colon, so a split on the first
 * colon round-trips a name that itself contains colons). Keying on the participant key — the same
 * value {@code PokerVoteService} stores on each vote — lets the roster correlate a participant
 * with their vote without persisting or broadcasting any token. The hash's whole-key TTL is
 * refreshed on every {@link #register}; Redis hashes have no per-field expiry.
 */
@Service
public class PokerParticipantRegistryService {

    private static final Logger LOG = LoggerFactory.getLogger(PokerParticipantRegistryService.class);

    /** Redis key prefix for a room's participant roster hash. */
    private static final String ROSTER_KEY_PREFIX = "poker:room-roster:";

    /** Separator between the role and the display name in a stored roster value. */
    private static final char VALUE_SEPARATOR = ':';

    private final StringRedisTemplate redisTemplate;

    /**
     * Creates the service with the shared Redis client.
     *
     * @param redisTemplate Redis client used to store the roster hash
     */
    public PokerParticipantRegistryService(final StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Registers (or updates) a participant in a room's roster, refreshing the roster's overall
     * TTL to {@code ttl} from now.
     *
     * <p>Idempotent by participant key: re-registering the same access token (e.g. the facilitator
     * re-opening the tab) overwrites their entry rather than adding a duplicate, so {@link
     * #countActive} never double-counts.
     *
     * @param roomId      the room's identifier
     * @param accessToken the participant's room-scoped access token (hashed into the roster key)
     * @param name        the participant's chosen display name
     * @param role        the participant's role
     * @param ttl         how long the roster remains valid
     */
    public void register(
            final UUID roomId, final String accessToken, final String name,
            final ParticipantRole role, final Duration ttl) {
        String key = rosterKey(roomId);
        String field = PokerParticipantKey.of(accessToken);
        redisTemplate.opsForHash().put(key, field, role.name() + VALUE_SEPARATOR + name);
        redisTemplate.expire(key, ttl);
        LOG.info("Room participant registered: room={} role={} ttlSeconds={}", roomId, role, ttl.toSeconds());
    }

    /**
     * Reads a room's current roster.
     *
     * @param roomId the room's identifier
     * @return the roster members (name + role, keyed by participant key), or an empty list if the
     *     room has no registered participant (or its roster has fully expired). Entries that are
     *     malformed (missing the role/name separator) are skipped defensively.
     */
    public List<RosterMember> roster(final UUID roomId) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(rosterKey(roomId));
        List<RosterMember> members = new ArrayList<>(entries.size());
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String stored = String.valueOf(entry.getValue());
            int separator = stored.indexOf(VALUE_SEPARATOR);
            if (separator < 0) {
                // Defensively skip a malformed entry — can't happen for values this service writes,
                // and nothing here is logged (the roomId arrives via a user-controlled WS
                // destination, so logging it would be a log-forging sink — Sonar S5145).
                continue;
            }
            ParticipantRole role = ParticipantRole.fromNullable(stored.substring(0, separator));
            String name = stored.substring(separator + 1);
            members.add(new RosterMember(String.valueOf(entry.getKey()), name, role));
        }
        return members;
    }

    /**
     * Counts the distinct participants currently registered for a room.
     *
     * @param roomId the room's identifier
     * @return the roster size, or {@code 0} if empty/expired
     */
    public long countActive(final UUID roomId) {
        Long size = redisTemplate.opsForHash().size(rosterKey(roomId));
        return size != null ? size : 0L;
    }

    /**
     * Builds the Redis key for a given room's roster.
     *
     * @param roomId the room's identifier
     * @return the Redis key
     */
    private String rosterKey(final UUID roomId) {
        return ROSTER_KEY_PREFIX + roomId;
    }

    /**
     * A resolved roster member (server-side view — includes the participant key so the roster can
     * be correlated with the vote store; never broadcast verbatim).
     *
     * @param participantKey the participant's {@link PokerParticipantKey}
     * @param name           the participant's display name
     * @param role           the participant's role
     */
    public record RosterMember(String participantKey, String name, ParticipantRole role) {
    }
}
