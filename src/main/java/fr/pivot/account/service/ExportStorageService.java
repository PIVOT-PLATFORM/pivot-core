package fr.pivot.account.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Local-filesystem storage for generated export archives (US02.3.1).
 *
 * <p>MVP storage choice — a message queue / object storage is not warranted for a low-volume,
 * single-user background job. The base path is configurable ({@code pivot.export.storage-path})
 * and excluded from version control.
 *
 * <p>Paths are built exclusively from internal numeric identifiers (user id, request id
 * primary keys) — never from user-controlled input — so there is no path-traversal surface
 * (CWE-22).
 */
@Service
public class ExportStorageService {

    private static final Logger LOG = LoggerFactory.getLogger(ExportStorageService.class);

    private final Path basePath;

    /**
     * @param storagePath base directory for generated archives, one subdirectory per user id
     */
    public ExportStorageService(@Value("${pivot.export.storage-path:./data/exports}") final String storagePath) {
        this.basePath = Path.of(storagePath).toAbsolutePath().normalize();
    }

    /**
     * Writes an archive to {@code {basePath}/{userId}/export-{requestId}.zip}.
     *
     * @param userId    owner of the archive (used as the storage subdirectory)
     * @param requestId the {@code data_export_requests} primary key
     * @param content   the ZIP archive bytes
     * @return the absolute path the archive was written to
     * @throws IOException if the directory cannot be created or the file cannot be written
     */
    public String store(final Long userId, final Long requestId, final byte[] content) throws IOException {
        final Path dir = basePath.resolve(String.valueOf(userId));
        Files.createDirectories(dir);
        final Path file = dir.resolve("export-" + requestId + ".zip");
        Files.write(file, content);
        return file.toString();
    }

    /**
     * Reads a previously stored archive back into memory for download streaming.
     *
     * @param filePath the absolute path returned by {@link #store}
     * @return the archive bytes
     * @throws IOException if the file cannot be read
     */
    public byte[] read(final String filePath) throws IOException {
        return Files.readAllBytes(Path.of(filePath));
    }

    /**
     * Best-effort deletion of an archive file — used by the expiry cleanup job.
     * Never throws: a missing or unreadable file must not block row cleanup.
     *
     * @param filePath the absolute path to delete, or {@code null} (no-op)
     */
    public void delete(final String filePath) {
        if (filePath == null) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(filePath));
        } catch (final IOException e) {
            LOG.warn("event=EXPORT_FILE_DELETE_FAILED path={} error={}", filePath, e.getMessage());
        }
    }
}
