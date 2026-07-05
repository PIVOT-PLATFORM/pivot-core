package fr.pivot.account.service;

import fr.pivot.account.config.AvatarStorageProperties;
import fr.pivot.account.exception.AvatarStorageException;
import fr.pivot.account.exception.AvatarTooLargeException;
import fr.pivot.account.exception.InvalidAvatarFormatException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Minimal local-filesystem avatar storage (US02.1.1). See {@link AvatarStorageProperties}
 * and {@code fr.pivot.account.config.AvatarWebConfig} for the surrounding design rationale.
 *
 * <p>Format is verified by sniffing the file's magic bytes, never by trusting the
 * client-supplied {@code Content-Type} header alone (a request can freely declare
 * {@code image/png} while sending arbitrary bytes) — Red Team consideration for an upload
 * endpoint. Only JPEG, PNG and WEBP are accepted.
 *
 * <p>Stored filenames are a random {@link UUID}, never derived from {@code userId} — this
 * keeps the public {@code /avatars/**} static route (unauthenticated by design, see
 * {@code AvatarWebConfig}) from leaking an enumerable {@code {tenantId}/{userId}} identifier
 * space, and a fresh name is minted on every re-upload so a previously-shared URL for a
 * replaced avatar naturally goes stale.
 */
@Service
public class AvatarStorageService {

    private static final Logger LOG = LoggerFactory.getLogger(AvatarStorageService.class);

    /** 2 MiB — US02.1.1 AC: "taille max 2 Mo". */
    private static final long MAX_SIZE_BYTES = 2L * 1024 * 1024;

    /** External-facing URL prefix — matches {@code AvatarWebConfig}'s {@code /avatars/**} mapping,
     * fronted by the {@code /api} servlet context path (see {@code server.servlet.context-path}). */
    private static final String AVATAR_URL_PREFIX = "/api/avatars/";

    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private final AvatarStorageProperties properties;

    /**
     * Constructs the service with its storage configuration.
     *
     * @param properties bound {@code pivot.storage.avatars.*} configuration
     */
    public AvatarStorageService(final AvatarStorageProperties properties) {
        this.properties = properties;
    }

    /**
     * Validates and persists an avatar file for the given tenant.
     *
     * @param tenantId identifier of the uploader's tenant — resolved by the caller exclusively
     *                 from the bearer token, never accepted here from request input
     * @param file     the uploaded multipart file
     * @return the URL under which the stored avatar is publicly reachable
     * @throws InvalidAvatarFormatException if the file is empty or not JPEG/PNG/WEBP
     * @throws AvatarTooLargeException      if the file exceeds 2&nbsp;MB
     * @throws AvatarStorageException       if the file cannot be written to disk
     */
    public String store(final Long tenantId, final MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidAvatarFormatException("empty");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new AvatarTooLargeException(file.getSize());
        }

        final byte[] content;
        try {
            content = file.getBytes();
        } catch (final IOException e) {
            throw new AvatarStorageException("Unable to read uploaded avatar", e);
        }

        final String extension = detectExtension(content, file.getContentType());

        try {
            final Path tenantDir = Path.of(properties.basePath(), String.valueOf(tenantId)).normalize();
            Files.createDirectories(tenantDir);
            final String filename = UUID.randomUUID() + "." + extension;
            Files.write(tenantDir.resolve(filename), content);
            LOG.info("event=AVATAR_STORED tenantId={}", tenantId);
            return AVATAR_URL_PREFIX + tenantId + "/" + filename;
        } catch (final IOException e) {
            throw new AvatarStorageException("Unable to store avatar", e);
        }
    }

    /**
     * Best-effort deletion of a previously stored avatar, e.g. when it is replaced by a new
     * upload. Never throws — a leftover orphan file is a minor cleanup issue, not a request
     * failure. No-op for URLs not managed by this service (e.g. an external Google avatar URL).
     *
     * @param avatarUrl the previous {@code avatarUrl} value, or {@code null}
     */
    public void deleteIfManaged(final String avatarUrl) {
        if (avatarUrl == null || !avatarUrl.startsWith(AVATAR_URL_PREFIX)) {
            return;
        }
        try {
            final Path base = Path.of(properties.basePath()).toAbsolutePath().normalize();
            final Path target = base.resolve(avatarUrl.substring(AVATAR_URL_PREFIX.length())).normalize();
            // Defense-in-depth: never delete outside the configured base directory.
            if (target.startsWith(base)) {
                Files.deleteIfExists(target);
            }
        } catch (final IOException e) {
            LOG.warn("event=AVATAR_DELETE_FAILED reason={}", e.getMessage());
        }
    }

    private static String detectExtension(final byte[] content, final String declaredContentType) {
        if (startsWith(content, JPEG_MAGIC)) {
            return "jpg";
        }
        if (startsWith(content, PNG_MAGIC)) {
            return "png";
        }
        if (isWebp(content)) {
            return "webp";
        }
        throw new InvalidAvatarFormatException(String.valueOf(declaredContentType));
    }

    private static boolean startsWith(final byte[] data, final byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** WEBP: {@code "RIFF"} at bytes 0-3, {@code "WEBP"} at bytes 8-11 (RIFF container). */
    private static boolean isWebp(final byte[] data) {
        return data.length >= 12
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
    }
}
