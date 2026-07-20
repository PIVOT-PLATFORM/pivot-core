package fr.pivot.agilite.poker.ws;

import fr.pivot.agilite.AgiliteTestContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test proving {@link PokerParticipantRegistryService} against a real Redis instance
 * (US09.2.1 + E09 named roster) — registration, roster count, the named roster read, role/name
 * round-trip, cross-room isolation, and TTL-driven expiry.
 *
 * <p>Instantiates the service directly against a real {@link StringRedisTemplate}, same
 * convention as {@code RoomAccessGrantServiceIT} — a single collaborator, no Spring context
 * needed.
 *
 * <p>EN53.1 — references the module-wide {@link AgiliteTestContainers#REDIS} singleton directly
 * (no {@code @SpringBootTest}, see {@code RoomAccessGrantServiceIT}'s Javadoc for the rationale).
 */
class PokerParticipantRegistryServiceIT {

    private static final GenericContainer<?> redis = AgiliteTestContainers.REDIS;
    private static final Duration TTL = Duration.ofMinutes(5);

    private LettuceConnectionFactory connectionFactory;
    private PokerParticipantRegistryService registryService;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        registryService = new PokerParticipantRegistryService(redisTemplate);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    /**
     * Given no participant has ever registered for a room, when its roster is counted, then it
     * is zero and the named roster is empty.
     */
    @Test
    void emptyRosterWithoutAnyRegistration() {
        UUID roomId = UUID.randomUUID();
        assertThat(registryService.countActive(roomId)).isZero();
        assertThat(registryService.roster(roomId)).isEmpty();
    }

    /**
     * Given one participant registered, when the roster is read, then it carries that
     * participant's name and role (round-tripped through Redis).
     */
    @Test
    void registerThenRosterReturnsNameAndRole() {
        UUID roomId = UUID.randomUUID();
        registryService.register(roomId, "token-1", "Alice", ParticipantRole.JOUEUR, TTL);

        assertThat(registryService.countActive(roomId)).isEqualTo(1L);
        List<PokerParticipantRegistryService.RosterMember> roster = registryService.roster(roomId);
        assertThat(roster).singleElement().satisfies(member -> {
            assertThat(member.name()).isEqualTo("Alice");
            assertThat(member.role()).isEqualTo(ParticipantRole.JOUEUR);
            assertThat(member.participantKey()).isEqualTo(PokerParticipantKey.of("token-1"));
        });
    }

    /**
     * Given a display name that itself contains a colon, when the roster is read back, then the
     * whole name is preserved (the role/name split is on the first colon only).
     */
    @Test
    void nameContainingColonRoundTrips() {
        UUID roomId = UUID.randomUUID();
        registryService.register(roomId, "token-1", "Ann: the PO", ParticipantRole.VISITEUR, TTL);

        assertThat(registryService.roster(roomId)).singleElement().satisfies(member -> {
            assertThat(member.name()).isEqualTo("Ann: the PO");
            assertThat(member.role()).isEqualTo(ParticipantRole.VISITEUR);
        });
    }

    /**
     * Given two distinct access tokens registered for the same room, when the roster is counted
     * and read, then it reflects both distinct participants.
     */
    @Test
    void rosterReflectsDistinctParticipants() {
        UUID roomId = UUID.randomUUID();
        registryService.register(roomId, "token-1", "Alice", ParticipantRole.JOUEUR, TTL);
        registryService.register(roomId, "token-2", "Bob", ParticipantRole.VISITEUR, TTL);

        assertThat(registryService.countActive(roomId)).isEqualTo(2L);
        assertThat(registryService.roster(roomId))
                .extracting(PokerParticipantRegistryService.RosterMember::name)
                .containsExactlyInAnyOrder("Alice", "Bob");
    }

    /**
     * Given the same access token registered twice (idempotent re-join, possibly with an updated
     * name), when the roster is counted, then it is still one — the hash field is overwritten.
     */
    @Test
    void registeringSameTokenTwiceOverwritesInsteadOfDuplicating() {
        UUID roomId = UUID.randomUUID();
        registryService.register(roomId, "token-1", "Alice", ParticipantRole.JOUEUR, TTL);
        registryService.register(roomId, "token-1", "Alice renamed", ParticipantRole.VISITEUR, TTL);

        assertThat(registryService.countActive(roomId)).isEqualTo(1L);
        assertThat(registryService.roster(roomId)).singleElement().satisfies(member -> {
            assertThat(member.name()).isEqualTo("Alice renamed");
            assertThat(member.role()).isEqualTo(ParticipantRole.VISITEUR);
        });
    }

    /**
     * Security AC (cross-room isolation): given participants registered for one room, when a
     * different room's roster is read, then it is unaffected.
     */
    @Test
    void registrationForOneRoomDoesNotAffectAnotherRoom() {
        UUID roomId = UUID.randomUUID();
        UUID otherRoomId = UUID.randomUUID();
        registryService.register(roomId, "token-1", "Alice", ParticipantRole.JOUEUR, TTL);

        assertThat(registryService.countActive(otherRoomId)).isZero();
        assertThat(registryService.roster(otherRoomId)).isEmpty();
    }

    /**
     * Given a roster registered with a very short TTL, when the TTL elapses without any further
     * registration, then the roster empties out — proving expiry is real, not just accepted as a
     * parameter.
     */
    @Test
    void rosterExpiresAfterItsTtl() throws InterruptedException {
        UUID roomId = UUID.randomUUID();
        registryService.register(roomId, "token-1", "Alice", ParticipantRole.JOUEUR, Duration.ofSeconds(1));
        assertThat(registryService.countActive(roomId)).isEqualTo(1L);

        Thread.sleep(1500);

        assertThat(registryService.countActive(roomId)).isZero();
        assertThat(registryService.roster(roomId)).isEmpty();
    }
}
