package fr.pivot.account.service;

import fr.pivot.account.dto.ProfileDto;
import fr.pivot.account.dto.ProfileUpdateRequest;
import fr.pivot.account.exception.InvalidPreferredLanguageException;
import fr.pivot.account.exception.InvalidProfileNameException;
import fr.pivot.account.util.HtmlStripper;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.UserRepository;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Business logic for viewing and editing the current user's account profile (US02.1.1,
 * US02.1.2).
 *
 * <p>Identity is always supplied by the caller ({@code AccountController}), resolved
 * exclusively from the bearer token — this service never looks up a user by an id coming
 * from request input.
 */
@Service
public class ProfileService {

    private static final Logger LOG = LoggerFactory.getLogger(ProfileService.class);

    private final UserRepository userRepository;
    private final AvatarStorageService avatarStorageService;

    /**
     * Constructs the service with its collaborators.
     *
     * @param userRepository       persists profile changes
     * @param avatarStorageService validates and persists avatar files
     */
    public ProfileService(final UserRepository userRepository, final AvatarStorageService avatarStorageService) {
        this.userRepository = userRepository;
        this.avatarStorageService = avatarStorageService;
    }

    /**
     * Returns the profile of the given user.
     *
     * @param user the authenticated user
     * @return the user's profile DTO
     */
    public ProfileDto getProfile(final User user) {
        return ProfileDto.from(user);
    }

    /** US02.1.1 AC: "max 100 caractères". Checked on the raw (pre-strip) value. */
    private static final int MAX_NAME_LENGTH = 100;

    /** US02.1.2 AC: "choisir ma langue préférée (FR/EN)" — the only two accepted values. */
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("fr", "en");

    /**
     * Updates {@code firstName}/{@code lastName}, and optionally {@code preferredLanguage},
     * for the given user.
     *
     * <p>Validation order: (1) raw presence/length ({@code null}/blank or over
     * {@value #MAX_NAME_LENGTH} chars is rejected outright), (2) HTML stripping
     * ({@link HtmlStripper}) — the name is shown to other users (e.g. admin user list,
     * US06.1.x), so it must never carry markup, (3) a second blank check, since a value can
     * strip down to nothing (e.g. {@code "<script></script>"}), (4) if {@code preferredLanguage}
     * is present, it must be {@code fr}/{@code en} (case-insensitive) — any other value is
     * rejected outright, it is never defaulted or silently ignored.
     *
     * @param user    the authenticated user
     * @param request the requested first/last name and, optionally, preferred language
     * @return the updated profile DTO
     * @throws InvalidProfileNameException        if a name is missing, blank, over
     *     {@value #MAX_NAME_LENGTH} characters, or blank once HTML has been stripped
     * @throws InvalidPreferredLanguageException if {@code preferredLanguage} is present but
     *     is neither {@code fr} nor {@code en} (case-insensitive)
     */
    @Transactional
    public ProfileDto updateProfile(final User user, final ProfileUpdateRequest request) {
        validateRaw(request.firstName());
        validateRaw(request.lastName());

        final String firstName = HtmlStripper.stripTags(request.firstName()).trim();
        final String lastName = HtmlStripper.stripTags(request.lastName()).trim();

        if (firstName.isBlank() || lastName.isBlank()) {
            throw new InvalidProfileNameException(
                    "Le prénom et le nom ne peuvent pas être constitués uniquement de balises HTML.");
        }

        user.setFirstName(firstName);
        user.setLastName(lastName);
        if (request.preferredLanguage() != null) {
            user.setLocale(normalizeLanguage(request.preferredLanguage()));
        }
        final User saved = userRepository.save(user);

        LOG.info("event=PROFILE_UPDATED userId={}", user.getId());
        return ProfileDto.from(saved);
    }

    private static void validateRaw(final String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidProfileNameException("Le prénom et le nom sont obligatoires.");
        }
        if (value.length() > MAX_NAME_LENGTH) {
            throw new InvalidProfileNameException(
                    "Le prénom et le nom sont limités à " + MAX_NAME_LENGTH + " caractères.");
        }
    }

    /**
     * Validates and normalizes a {@code preferredLanguage} value to the persisted form.
     *
     * @param value the raw value from the request (never {@code null} — callers only invoke
     *              this when {@link ProfileUpdateRequest#preferredLanguage()} is present)
     * @return {@code value} lower-cased, ready to persist in {@code User.locale}
     * @throws InvalidPreferredLanguageException if {@code value} is not {@code fr}/{@code en}
     *     once lower-cased
     */
    private static String normalizeLanguage(final String value) {
        final String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_LANGUAGES.contains(normalized)) {
            throw new InvalidPreferredLanguageException(
                    "La langue préférée doit être 'fr' ou 'en'.");
        }
        return normalized;
    }

    /**
     * Replaces the avatar of the given user, deleting the previous file (if locally managed).
     *
     * @param user the authenticated user
     * @param file the uploaded avatar file
     * @return the updated profile DTO, with the new {@code avatarUrl}
     * @throws fr.pivot.account.exception.InvalidAvatarFormatException if the file is not a
     *     valid JPEG/PNG/WEBP image
     * @throws fr.pivot.account.exception.AvatarTooLargeException      if the file exceeds 2&nbsp;MB
     */
    @Transactional
    public ProfileDto updateAvatar(final User user, final MultipartFile file) {
        final String previousAvatarUrl = user.getAvatarUrl();
        final String newAvatarUrl = avatarStorageService.store(user.getTenant().getId(), file);

        user.setAvatarUrl(newAvatarUrl);
        final User saved = userRepository.save(user);
        avatarStorageService.deleteIfManaged(previousAvatarUrl);

        LOG.info("event=AVATAR_UPDATED userId={}", user.getId());
        return ProfileDto.from(saved);
    }
}
