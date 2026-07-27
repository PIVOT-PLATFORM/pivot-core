package fr.pivot.collaboratif.bingo;

import fr.pivot.collaboratif.bingo.dto.BingoGridResponse;
import fr.pivot.collaboratif.bingo.dto.BingoRoomResponse;
import fr.pivot.collaboratif.bingo.dto.CellDto;
import fr.pivot.collaboratif.bingo.dto.GridDto;
import fr.pivot.collaboratif.bingo.dto.ParticipantJoinedEvent;
import fr.pivot.collaboratif.bingo.exception.BingoRoomNotFoundException;
import fr.pivot.collaboratif.bingo.exception.InvalidCodeException;
import fr.pivot.collaboratif.bingo.exception.InvalidDisplayNameException;
import fr.pivot.collaboratif.bingo.ws.BingoPresenceRegistryService;
import fr.pivot.collaboratif.bingo.ws.BingoRoomAccessGrantService;
import fr.pivot.collaboratif.bingo.ws.BingoRoomDestinations;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for Bingo room creation, join-by-code (authenticated and anonymous), and grid
 * re-fetch (US47.1.1, AC-47.1.1-01/02/03/04/05/13).
 */
@Service
public class BingoRoomService {

    /** Grid dimension — always exactly 25 cells (5x5, AC-47.1.1-04). */
    private static final int GRID_SIZE = 25;

    /** Invariant: the shared phrase bank must have at least this many phrases. */
    private static final int MIN_BANK_SIZE = 25;

    /** Maximum attempts to find a free invite code before giving up (collision near-impossible). */
    private static final int MAX_INVITE_CODE_ATTEMPTS = 5;

    private static final int INVITE_CODE_LENGTH = 6;

    private static final int MIN_DISPLAY_NAME_LENGTH = 2;
    private static final int MAX_DISPLAY_NAME_LENGTH = 30;

    private final BingoRoomRepository roomRepository;
    private final BingoGridRepository gridRepository;
    private final BingoGridCellRepository gridCellRepository;
    private final BingoPhraseRepository phraseRepository;
    private final BingoRoomAccessGrantService roomAccessGrantService;
    private final BingoPresenceRegistryService presenceRegistryService;
    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;
    private final int defaultExpirationHours;
    private final int maxPlayers;

    /**
     * Constructs the service.
     *
     * @param roomRepository          room persistence
     * @param gridRepository          grid persistence
     * @param gridCellRepository      grid cell persistence
     * @param phraseRepository        shared phrase bank persistence
     * @param roomAccessGrantService  Redis-backed WebSocket access grant store (SEC-01)
     * @param presenceRegistryService Redis-backed live player/spectator roster
     * @param messagingTemplate       STOMP broadcast template
     * @param clock                   clock used to timestamp rooms (overridable in tests)
     * @param defaultExpirationHours  room lifetime in hours (property
     *                                {@code pivot.collaboratif.bingo.room.default-expiration-hours},
     *                                24 by default)
     * @param maxPlayers              player threshold before spectator degradation (property
     *                                {@code pivot.collaboratif.bingo.room.max-players}, 50 by
     *                                default, AC-47.1.1-13)
     */
    public BingoRoomService(
            final BingoRoomRepository roomRepository,
            final BingoGridRepository gridRepository,
            final BingoGridCellRepository gridCellRepository,
            final BingoPhraseRepository phraseRepository,
            final BingoRoomAccessGrantService roomAccessGrantService,
            final BingoPresenceRegistryService presenceRegistryService,
            final SimpMessagingTemplate messagingTemplate,
            final Clock clock,
            @Value("${pivot.collaboratif.bingo.room.default-expiration-hours:24}") final int defaultExpirationHours,
            @Value("${pivot.collaboratif.bingo.room.max-players:50}") final int maxPlayers) {
        this.roomRepository = roomRepository;
        this.gridRepository = gridRepository;
        this.gridCellRepository = gridCellRepository;
        this.phraseRepository = phraseRepository;
        this.roomAccessGrantService = roomAccessGrantService;
        this.presenceRegistryService = presenceRegistryService;
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
        this.defaultExpirationHours = defaultExpirationHours;
        this.maxPlayers = maxPlayers;
    }

    /**
     * Creates a new Bingo room. The caller becomes its first player immediately — their own grid
     * is generated and their WebSocket access grant minted before the response is returned
     * (AC-47.1.1-01).
     *
     * @param name          the room's display name (already validated by the controller)
     * @param creatorUserId the caller's user id, resolved server-side from the bearer token
     * @param tenantId      the caller's tenant id, resolved server-side from the bearer token
     * @return the created room, including the creator's grid and freshly minted {@code accessToken}
     */
    @Transactional
    public BingoRoomResponse create(final String name, final Long creatorUserId, final Long tenantId) {
        ensureBankReady();
        final Instant now = clock.instant();
        final String code = generateUniqueCode();
        final BingoRoom room = roomRepository.save(new BingoRoom(
                code, name, creatorUserId, tenantId, maxPlayers, now, now.plus(defaultExpirationHours, ChronoUnit.HOURS)));

        final String accessToken = UUID.randomUUID().toString();
        final String participantKey = BingoTokenHasher.hash(accessToken);
        final String displayName = authenticatedDisplayName(creatorUserId);
        final BingoGrid grid = generateGrid(room.getId(), participantKey, displayName);
        final Duration ttl = Duration.between(now, room.getExpiresAt());

        grantAndRegister(room.getId(), accessToken, BingoParticipantRole.PLAYER, ttl);
        broadcastJoined(room.getId(), grid.getId(), displayName);

        return toResponse(room, code, accessToken, BingoParticipantRole.PLAYER, toGridDto(grid));
    }

    /**
     * Joins an existing room by invite code — authenticated (AC-47.1.1-02) or anonymous
     * (AC-47.1.1-03, {@code callerUserId == null}). Admits the caller as a {@code PLAYER} while
     * under {@code maxPlayers}, otherwise as a {@code SPECTATOR} (AC-47.1.1-13) — never a
     * blocking 4xx for a full room.
     *
     * @param code          the 6-character invite code
     * @param callerUserId  the caller's user id if authenticated, or {@code null} for an anonymous
     *                      join
     * @param rawDisplayName the caller-supplied pseudonym — required for an anonymous join
     *                      (AC-47.1.1-17), ignored for an authenticated one
     * @return the join response, including a freshly minted {@code accessToken}
     * @throws InvalidCodeException        if {@code code} fails shape validation (AC-47.1.1-15)
     * @throws BingoRoomNotFoundException  if the code does not resolve to a currently joinable
     *                                     room (AC-47.1.1-16, anti-enumeration)
     * @throws InvalidDisplayNameException if an anonymous join's {@code rawDisplayName} fails
     *                                     validation (AC-47.1.1-17)
     */
    @Transactional
    public BingoRoomResponse join(final String code, final Long callerUserId, final String rawDisplayName) {
        validateCode(code);
        final Instant now = clock.instant();
        final BingoRoom room = roomRepository.findByCode(code)
                .filter(candidate -> candidate.isJoinable(now))
                .orElseThrow(BingoRoomNotFoundException::new);

        final String displayName = callerUserId != null
                ? authenticatedDisplayName(callerUserId)
                : validateAndSanitizeAnonymousDisplayName(rawDisplayName);

        final String accessToken = UUID.randomUUID().toString();
        final Duration ttl = Duration.between(now, room.getExpiresAt());
        final int currentPlayers = presenceRegistryService.countPlayers(room.getId());

        final BingoParticipantRole role;
        final GridDto gridDto;
        final UUID participantId;
        if (currentPlayers >= room.getMaxPlayers()) {
            role = BingoParticipantRole.SPECTATOR;
            gridDto = null;
            participantId = UUID.randomUUID();
        } else {
            role = BingoParticipantRole.PLAYER;
            final BingoGrid grid = generateGrid(room.getId(), BingoTokenHasher.hash(accessToken), displayName);
            gridDto = toGridDto(grid);
            participantId = grid.getId();
        }

        grantAndRegister(room.getId(), accessToken, role, ttl);
        broadcastJoined(room.getId(), participantId, displayName);

        return toResponse(room, null, accessToken, role, gridDto);
    }

    /**
     * Re-fetches the caller's own grid, for reconnection without regenerating it (AC-47.1.1-05).
     *
     * @param roomId      the room id
     * @param accessToken the caller's access token
     * @return the caller's grid and current room status
     * @throws BingoRoomNotFoundException if the room does not exist or the caller has no valid
     *                                    grant for it — always the same generic outcome
     *                                    (AC-47.1.1-20, never 403)
     */
    @Transactional(readOnly = true)
    public BingoGridResponse getGrid(final UUID roomId, final String accessToken) {
        if (!roomAccessGrantService.hasAccess(roomId, accessToken)) {
            throw new BingoRoomNotFoundException();
        }
        final BingoRoom room = roomRepository.findById(roomId).orElseThrow(BingoRoomNotFoundException::new);
        final Optional<BingoGrid> grid =
                gridRepository.findByRoomIdAndParticipantKey(roomId, BingoTokenHasher.hash(accessToken));
        final BingoParticipantRole role = grid.isPresent() ? BingoParticipantRole.PLAYER : BingoParticipantRole.SPECTATOR;
        final GridDto gridDto = grid.map(this::toGridDto).orElse(null);
        return new BingoGridResponse(roomId, room.getStatus().name(), role.name(), gridDto);
    }

    private void ensureBankReady() {
        if (phraseRepository.count() < MIN_BANK_SIZE) {
            throw new IllegalStateException("Bingo phrase bank has fewer than " + MIN_BANK_SIZE + " phrases");
        }
    }

    private BingoGrid generateGrid(final UUID roomId, final String participantKey, final String displayName) {
        final List<BingoPhrase> phrases = phraseRepository.drawRandom(GRID_SIZE);
        if (phrases.size() < GRID_SIZE) {
            throw new IllegalStateException("Bingo phrase bank has fewer than " + GRID_SIZE + " phrases");
        }
        final BingoGrid grid = gridRepository.save(new BingoGrid(roomId, participantKey, displayName, clock.instant()));
        final List<BingoGridCell> cells = new ArrayList<>(GRID_SIZE);
        for (int i = 0; i < GRID_SIZE; i++) {
            BingoPhrase phrase = phrases.get(i);
            cells.add(new BingoGridCell(grid.getId(), i, phrase.getId(), phrase.getPhrase()));
        }
        gridCellRepository.saveAll(cells);
        return grid;
    }

    private void grantAndRegister(
            final UUID roomId, final String accessToken, final BingoParticipantRole role, final Duration ttl) {
        roomAccessGrantService.grantAccess(roomId, accessToken, ttl);
        presenceRegistryService.register(roomId, accessToken, role, ttl);
    }

    private void broadcastJoined(final UUID roomId, final UUID participantId, final String displayName) {
        int players = presenceRegistryService.countPlayers(roomId);
        int spectators = presenceRegistryService.countSpectators(roomId);
        messagingTemplate.convertAndSend(
                BingoRoomDestinations.roomTopic(roomId),
                ParticipantJoinedEvent.of(roomId, participantId, displayName, players, spectators));
    }

    private void validateCode(final String code) {
        if (code == null || code.isBlank() || code.length() != INVITE_CODE_LENGTH) {
            throw new InvalidCodeException();
        }
    }

    /**
     * Validates and sanitizes an anonymous join's caller-supplied pseudonym (AC-47.1.1-17,
     * SEC-05): 2-30 non-whitespace-only characters, control characters stripped as defense in
     * depth (the frontend renders it as plain text regardless, never {@code innerHTML}).
     *
     * @param raw the caller-supplied pseudonym
     * @return the sanitized, validated display name
     * @throws InvalidDisplayNameException if {@code raw} fails validation
     */
    private String validateAndSanitizeAnonymousDisplayName(final String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidDisplayNameException();
        }
        final String trimmed = raw.trim();
        if (trimmed.length() < MIN_DISPLAY_NAME_LENGTH || trimmed.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new InvalidDisplayNameException();
        }
        final String sanitized = stripControlCharacters(trimmed);
        if (sanitized.isBlank()) {
            throw new InvalidDisplayNameException();
        }
        return sanitized;
    }

    private static String stripControlCharacters(final String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isISOControl(c)) {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    /**
     * Derives a stable display name for an authenticated participant from their platform identity
     * alone (AC-47.1.1-17: an authenticated join ignores any caller-supplied {@code displayName}).
     * This module has no cross-module access to a user's real profile name (no shared {@code
     * public.users} read API is exported by {@code pivot-core-starter} — see the module's
     * {@code CLAUDE.md} exported-package table); a stable, non-enumerable-by-others label derived
     * from the platform user id is used instead.
     *
     * @param userId the caller's {@code public.users.id}
     * @return the derived display name
     */
    private static String authenticatedDisplayName(final Long userId) {
        return "Joueur-" + userId;
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < MAX_INVITE_CODE_ATTEMPTS; attempt++) {
            final String candidate = BingoInviteCodeGenerator.generate();
            if (!roomRepository.existsByCode(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Unable to generate a unique Bingo invite code after " + MAX_INVITE_CODE_ATTEMPTS + " attempts");
    }

    private BingoRoomResponse toResponse(
            final BingoRoom room, final String code, final String accessToken,
            final BingoParticipantRole role, final GridDto grid) {
        return new BingoRoomResponse(
                room.getId(), code, room.getName(), room.getStatus().name(), room.getMaxPlayers(),
                room.getExpiresAt(), BingoRoomDestinations.roomTopic(room.getId()), accessToken, role.name(), grid);
    }

    private GridDto toGridDto(final BingoGrid grid) {
        List<CellDto> cells = gridCellRepository.findByGridId(grid.getId()).stream()
                .sorted(Comparator.comparingInt(BingoGridCell::getCellIndex))
                .map(c -> new CellDto(c.getCellIndex(), c.getPhraseText(), c.isMarked()))
                .toList();
        return new GridDto(cells);
    }
}
