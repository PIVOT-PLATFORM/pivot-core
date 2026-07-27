package fr.pivot.collaboratif.bingo;

import fr.pivot.collaboratif.bingo.dto.BingoGridResponse;
import fr.pivot.collaboratif.bingo.dto.BingoRoomResponse;
import fr.pivot.collaboratif.bingo.dto.CreateBingoRoomRequest;
import fr.pivot.collaboratif.bingo.dto.JoinBingoRoomRequest;
import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.web.CollaboratifApiPaths;
import fr.pivot.core.auth.AuthenticatedPrincipal;
import fr.pivot.core.auth.AuthenticatedPrincipalResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/**
 * REST controller exposing Bingo room operations under {@code /bingo/rooms} (US47.1.1) — create
 * (authenticated only, AC-47.1.1-01), join by code (authenticated or anonymous,
 * AC-47.1.1-02/03), and grid re-fetch (AC-47.1.1-05).
 *
 * <p>The full path (including the application context) is
 * {@code /api/collaboratif/bingo/rooms/...}.
 */
@RestController
@RequestMapping(CollaboratifApiPaths.BASE + "/bingo/rooms")
public class BingoRoomController {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final BingoRoomService service;
    private final AuthenticatedPrincipalResolver principalResolver;

    /**
     * Creates the controller with its required dependencies.
     *
     * @param service           the room business logic service
     * @param principalResolver resolver used to optionally identify the caller on the join
     *                          endpoint, which accepts both authenticated and anonymous callers
     */
    public BingoRoomController(final BingoRoomService service, final AuthenticatedPrincipalResolver principalResolver) {
        this.service = service;
        this.principalResolver = principalResolver;
    }

    /**
     * Creates a new Bingo room. The caller is automatically its first player (AC-47.1.1-01).
     *
     * @param request   the room creation request
     * @param principal the resolved, mandatory caller identity — a missing/invalid bearer token
     *                  is rejected with HTTP 401 by {@code CollaboratifRequestPrincipalResolver}
     *                  before this method is even invoked
     * @return the created room with HTTP 201 Created
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BingoRoomResponse create(
            @RequestBody @Valid final CreateBingoRoomRequest request,
            final CollaboratifRequestPrincipal principal) {
        return service.create(request.name(), principal.userId(), principal.tenantId());
    }

    /**
     * Joins an existing Bingo room by invite code — authenticated if an {@code Authorization}
     * bearer header is present and valid, anonymous otherwise (AC-47.1.1-02/03). Never requires
     * authentication, unlike {@link #create}.
     *
     * @param request     the join request — invite code and, for an anonymous caller, a pseudonym
     * @param httpRequest the current HTTP request, used to optionally resolve a bearer token
     *                    without forcing HTTP 401 when one is absent
     * @return the join response with HTTP 200
     */
    @PostMapping("/join")
    public BingoRoomResponse join(
            @RequestBody final JoinBingoRoomRequest request,
            final HttpServletRequest httpRequest) {
        Long callerUserId = resolveOptionalPrincipal(httpRequest).map(AuthenticatedPrincipal::userId).orElse(null);
        return service.join(request.code(), callerUserId, request.displayName());
    }

    /**
     * Re-fetches the caller's own grid for reconnection (AC-47.1.1-05).
     *
     * @param roomId      the room id from the path
     * @param accessToken the caller's access token, presented via the {@code access-token}
     *                    request header — deliberately not a query parameter, consistent with
     *                    this platform's standing rule against ever placing a token in a URL
     * @return the caller's grid and room status with HTTP 200
     */
    @GetMapping("/{roomId}/grid")
    public BingoGridResponse getGrid(
            @PathVariable final UUID roomId,
            @RequestHeader("access-token") final String accessToken) {
        return service.getGrid(roomId, accessToken);
    }

    /**
     * Resolves the caller's {@link AuthenticatedPrincipal} if the request carries a valid bearer
     * token — mirrors {@code fr.pivot.collaboratif.session.SessionCallerResolver
     * #resolveOptionalPrincipal}, duplicated locally rather than reused across packages since
     * that class's other responsibilities are Module Session-specific.
     *
     * @param request the current HTTP request
     * @return the resolved principal, or empty if no valid bearer token is present
     */
    private Optional<AuthenticatedPrincipal> resolveOptionalPrincipal(final HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header == null
                || header.length() <= BEARER_PREFIX.length()
                || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return Optional.empty();
        }
        String rawToken = header.substring(BEARER_PREFIX.length()).trim();
        if (rawToken.isEmpty()) {
            return Optional.empty();
        }
        return principalResolver.resolve(rawToken);
    }
}
