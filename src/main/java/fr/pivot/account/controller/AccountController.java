package fr.pivot.account.controller;

import fr.pivot.account.dto.ProfileDto;
import fr.pivot.account.dto.ProfileUpdateRequest;
import fr.pivot.account.exception.AvatarTooLargeException;
import fr.pivot.account.exception.EmailFieldNotAllowedException;
import fr.pivot.account.exception.InvalidAvatarFormatException;
import fr.pivot.account.exception.InvalidProfileNameException;
import fr.pivot.account.service.ProfileService;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.service.AuditService;
import fr.pivot.config.CookieHelper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST controller for the current user's own account profile (US02.1.1 « Voir et éditer son
 * profil »).
 *
 * <p>Responsibility: resolve the authenticated {@link User} from the
 * {@link SecurityContextHolder} (same scheme as {@code AdminModuleController} — the
 * {@code TokenAuthenticationFilter} places the {@link User} entity in the current
 * {@link Authentication}'s details), delegate to {@link ProfileService}, and translate
 * domain exceptions to HTTP responses. No business logic lives in this controller.
 *
 * <p><strong>Identity — never accepted from request input:</strong> there is no
 * {@code userId}/{@code tenantId} path or body parameter on any endpoint here. The acting
 * user is exclusively the one resolved from the bearer token, per the {@code /api/account/*}
 * hard rule in {@code CLAUDE.md}.
 *
 * <p>All endpoints require authentication (enforced by {@code SecurityConfig}'s default
 * {@code anyRequest().authenticated()} — no {@code @PreAuthorize} role restriction needed,
 * any authenticated user may view/edit their own profile).
 */
@RestController
@RequestMapping("/account")
public class AccountController {

    private static final Logger LOG = LoggerFactory.getLogger(AccountController.class);
    private static final String HEADER_USER_AGENT = "User-Agent";

    private final ProfileService profileService;
    private final AuditService auditService;
    private final CookieHelper cookieHelper;

    /**
     * Constructs the controller with its collaborators.
     *
     * @param profileService business logic for reading/updating the profile
     * @param auditService   application audit trail
     * @param cookieHelper   shared client-IP resolution helper
     */
    public AccountController(
            final ProfileService profileService,
            final AuditService auditService,
            final CookieHelper cookieHelper) {
        this.profileService = profileService;
        this.auditService = auditService;
        this.cookieHelper = cookieHelper;
    }

    /**
     * Returns the authenticated user's profile.
     *
     * @return {@code 200} with the {@link ProfileDto}, or {@code 401} if the authentication
     *     context is invalid
     */
    @GetMapping("/profile")
    public ResponseEntity<ProfileDto> getProfile() {
        final User user = resolveUser();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(profileService.getProfile(user));
    }

    /**
     * Updates the authenticated user's {@code firstName}/{@code lastName}.
     *
     * <p>The body is read as a raw {@code Map} — not deserialized directly into
     * {@link ProfileUpdateRequest} — specifically so an {@code email} property can be detected
     * and rejected with {@code 400} regardless of the JSON mapper's own unknown-property
     * leniency (see {@link ProfileUpdateRequest} javadoc for why the more obvious
     * {@code @JsonIgnoreProperties} approach does not work here). Email changes are out of
     * scope (US02.2.2) and must never be silently accepted or ignored. Any other unexpected
     * property is silently dropped (only {@code firstName}/{@code lastName} are read) — not a
     * security concern, since nothing but those two fields is ever passed to the service.
     *
     * @param body    the raw request body
     * @param request incoming request (IP, User-Agent extraction for audit)
     * @return {@code 200} with the updated {@link ProfileDto} · {@code 400} on validation
     *     failure (missing/blank/too-long name) or if an {@code email} property is present ·
     *     {@code 401} if the authentication context is invalid
     */
    @PatchMapping("/profile")
    public ResponseEntity<ProfileDto> updateProfile(
            @RequestBody final Map<String, Object> body,
            final HttpServletRequest request) {
        final User user = resolveUser();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (body.containsKey("email")) {
            throw new EmailFieldNotAllowedException();
        }

        final ProfileUpdateRequest req = new ProfileUpdateRequest(
                asString(body.get("firstName")), asString(body.get("lastName")));
        final ProfileDto dto = profileService.updateProfile(user, req);
        auditService.log(user, AuditService.PROFILE_UPDATED,
                cookieHelper.clientIp(request), request.getHeader(HEADER_USER_AGENT));
        LOG.info("event=ACCOUNT_PROFILE_UPDATED userId={}", user.getId());
        return ResponseEntity.ok(dto);
    }

    /**
     * Uploads (or replaces) the authenticated user's avatar.
     *
     * @param file    the uploaded image — JPEG/PNG/WEBP, max 2&nbsp;MB
     * @param request incoming request (IP, User-Agent extraction for audit)
     * @return {@code 200} with the updated {@link ProfileDto} · {@code 400} if the file is
     *     missing, too large, or not a recognized image format · {@code 401} if the
     *     authentication context is invalid
     */
    @PostMapping(value = "/profile/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProfileDto> uploadAvatar(
            @RequestPart("file") final MultipartFile file,
            final HttpServletRequest request) {
        final User user = resolveUser();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        final ProfileDto dto = profileService.updateAvatar(user, file);
        auditService.log(user, AuditService.AVATAR_UPDATED,
                cookieHelper.clientIp(request), request.getHeader(HEADER_USER_AGENT));
        LOG.info("event=ACCOUNT_AVATAR_UPDATED userId={}", user.getId());
        return ResponseEntity.ok(dto);
    }

    // ----------------------------------------------------------------
    // Exception handling — local to this controller (no global handler)
    // ----------------------------------------------------------------

    /**
     * Translates a blank-after-stripping name into {@code 400 Bad Request}.
     *
     * @param ex the exception raised by {@link ProfileService}
     * @return {@code 400} error body
     */
    @ExceptionHandler(InvalidProfileNameException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidName(final InvalidProfileNameException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "INVALID_NAME",
                "message", "Le prénom et le nom sont obligatoires."));
    }

    /**
     * Translates a request carrying an {@code email} property into {@code 400 Bad Request}.
     *
     * @param ex the exception raised when the request body contains {@code email}
     * @return {@code 400} error body
     */
    @ExceptionHandler(EmailFieldNotAllowedException.class)
    public ResponseEntity<Map<String, Object>> handleEmailFieldNotAllowed(final EmailFieldNotAllowedException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "EMAIL_CHANGE_NOT_ALLOWED",
                "message", "La modification de l'adresse email n'est pas prise en charge par cet endpoint."));
    }

    /**
     * Translates an oversized avatar into {@code 400 Bad Request}.
     *
     * @param ex the exception raised by {@link fr.pivot.account.service.AvatarStorageService}
     * @return {@code 400} error body
     */
    @ExceptionHandler(AvatarTooLargeException.class)
    public ResponseEntity<Map<String, Object>> handleAvatarTooLarge(final AvatarTooLargeException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "AVATAR_TOO_LARGE",
                "message", "L'image dépasse la taille maximale autorisée (2 Mo)."));
    }

    /**
     * Safety net for uploads rejected by the servlet multipart layer before reaching
     * {@link #uploadAvatar} (request larger than {@code spring.servlet.multipart.max-file-size}).
     * Translated to the same body as {@link #handleAvatarTooLarge} for a consistent frontend
     * contract.
     *
     * @param ex the exception raised by the multipart resolver
     * @return {@code 400} error body
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSizeExceeded(final MaxUploadSizeExceededException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "AVATAR_TOO_LARGE",
                "message", "L'image dépasse la taille maximale autorisée (2 Mo)."));
    }

    /**
     * Translates an unrecognized/invalid avatar format into {@code 400 Bad Request}.
     *
     * @param ex the exception raised by {@link fr.pivot.account.service.AvatarStorageService}
     * @return {@code 400} error body
     */
    @ExceptionHandler(InvalidAvatarFormatException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidAvatarFormat(final InvalidAvatarFormatException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "AVATAR_INVALID_FORMAT",
                "message", "Format d'image non supporté (JPEG, PNG ou WEBP uniquement)."));
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    /**
     * Resolves the authenticated user from the security context.
     *
     * @return the authenticated {@link User}, or {@code null} if the authentication context is
     *     invalid (no details, or details not a {@link User})
     */
    private User resolveUser() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !(auth.getDetails() instanceof User user)) {
            LOG.warn("event=ACCOUNT_PROFILE_REJECTED reason=invalid_auth_details type={}",
                    auth == null || auth.getDetails() == null ? "null" : auth.getDetails().getClass().getName());
            return null;
        }
        return user;
    }

    /**
     * Safely reads a {@link String} value out of a raw JSON-derived map entry.
     *
     * @param value the raw value (may be {@code null}, a {@link String}, or any other JSON
     *              type — number, boolean, nested object/array — if the client sent the wrong type)
     * @return the value as a {@link String}, or {@code null} if absent or not a string
     *     ({@link ProfileService} rejects a {@code null} name as missing)
     */
    private static String asString(final Object value) {
        return value instanceof String str ? str : null;
    }
}
