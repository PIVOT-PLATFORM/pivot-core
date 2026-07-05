package fr.pivot.account.service;

import fr.pivot.account.config.AvatarStorageProperties;
import fr.pivot.account.exception.AvatarTooLargeException;
import fr.pivot.account.exception.InvalidAvatarFormatException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests unitaires pour {@link AvatarStorageService} (US02.1.1).
 *
 * <p>Traçabilité :
 * <ul>
 *   <li>AC "formats acceptés JPEG/PNG/WEBP" — {@code ac0211_avatar_01_*}</li>
 *   <li>Error case "format invalide" — {@code ac0211_avatar_err01_*}</li>
 *   <li>Error case "taille max 2 Mo dépassée" — {@code ac0211_avatar_err02_*}</li>
 *   <li>Security "Content-Type déclaré non fiable, sniff magic bytes" — {@code ac0211_avatar_sec01_*}</li>
 * </ul>
 */
class AvatarStorageServiceTest {

    private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02, 0x03};
    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02};
    private static final byte[] WEBP_BYTES =
            {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 0x01, 0x02};

    @TempDir
    Path tempDir;

    private AvatarStorageService service;

    @BeforeEach
    void setUp() {
        service = new AvatarStorageService(new AvatarStorageProperties(tempDir.toString()));
    }

    // ----------------------------------------------------------------
    // Accepted formats
    // ----------------------------------------------------------------

    @Test
    void ac0211_avatar_01_storesJpeg_andReturnsUrlUnderTenant() throws IOException {
        final MultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", JPEG_BYTES);

        final String url = service.store(7L, file);

        assertThat(url).matches("/api/avatars/7/[0-9a-f-]+\\.jpg");
        assertThat(storedFileExists(url)).isTrue();
    }

    @Test
    void ac0211_avatar_01_storesPng() {
        final MultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES);

        final String url = service.store(7L, file);

        assertThat(url).endsWith(".png");
    }

    @Test
    void ac0211_avatar_01_storesWebp() {
        final MultipartFile file = new MockMultipartFile("file", "photo.webp", "image/webp", WEBP_BYTES);

        final String url = service.store(7L, file);

        assertThat(url).endsWith(".webp");
    }

    @Test
    void ac0211_avatar_01_generatesDistinctFilename_onEachUpload() {
        final MultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", JPEG_BYTES);

        final String first = service.store(7L, file);
        final String second = service.store(7L, file);

        assertThat(first).isNotEqualTo(second);
    }

    // ----------------------------------------------------------------
    // Security — magic-byte sniffing, not the declared Content-Type
    // ----------------------------------------------------------------

    @Test
    void ac0211_avatar_sec01_rejectsSpoofedContentType_whenBytesDoNotMatch() {
        // Declares image/png but the actual bytes are plain text — must not be trusted.
        final MultipartFile file = new MockMultipartFile("file", "evil.png", "image/png", "not-an-image".getBytes());

        assertThatThrownBy(() -> service.store(7L, file))
                .isInstanceOf(InvalidAvatarFormatException.class);
    }

    // ----------------------------------------------------------------
    // Error cases
    // ----------------------------------------------------------------

    @Test
    void ac0211_avatar_err01_rejectsUnrecognizedFormat() {
        final MultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "%PDF-1.4".getBytes());

        assertThatThrownBy(() -> service.store(7L, file))
                .isInstanceOf(InvalidAvatarFormatException.class);
    }

    @Test
    void ac0211_avatar_err01_rejectsEmptyFile() {
        final MultipartFile file = new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> service.store(7L, file))
                .isInstanceOf(InvalidAvatarFormatException.class);
    }

    @Test
    void ac0211_avatar_err02_rejectsFileLargerThan2Mb() {
        final byte[] tooLarge = new byte[2 * 1024 * 1024 + 1];
        System.arraycopy(JPEG_BYTES, 0, tooLarge, 0, JPEG_BYTES.length);
        final MultipartFile file = new MockMultipartFile("file", "big.jpg", "image/jpeg", tooLarge);

        assertThatThrownBy(() -> service.store(7L, file))
                .isInstanceOf(AvatarTooLargeException.class);
    }

    // ----------------------------------------------------------------
    // deleteIfManaged
    // ----------------------------------------------------------------

    @Test
    void deleteIfManaged_removesPreviouslyStoredFile() {
        final MultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", JPEG_BYTES);
        final String url = service.store(7L, file);
        assertThat(storedFileExists(url)).isTrue();

        service.deleteIfManaged(url);

        assertThat(storedFileExists(url)).isFalse();
    }

    @Test
    void deleteIfManaged_isNoOp_forNullUrl() {
        service.deleteIfManaged(null);
        // No exception — nothing to assert beyond "did not throw".
    }

    @Test
    void deleteIfManaged_isNoOp_forExternalUrl() throws IOException {
        // e.g. a Google OAuth avatarUrl — never a locally-managed file.
        service.deleteIfManaged("https://lh3.googleusercontent.com/a/photo.jpg");

        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void deleteIfManaged_doesNotEscapeBaseDirectory_onPathTraversalAttempt() throws IOException {
        final Path outside = tempDir.resolveSibling("outside-avatar-storage.txt");
        Files.writeString(outside, "must-not-be-deleted");

        try {
            service.deleteIfManaged("/api/avatars/../../outside-avatar-storage.txt");

            assertThat(Files.exists(outside)).isTrue();
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    private boolean storedFileExists(final String url) {
        final String relative = url.substring("/api/avatars/".length());
        return Files.exists(tempDir.resolve(relative));
    }
}
