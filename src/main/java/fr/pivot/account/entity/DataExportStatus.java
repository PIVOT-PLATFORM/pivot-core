package fr.pivot.account.entity;

/**
 * Lifecycle status of a {@link DataExportRequest} (RGPD Art. 20 — US02.3.1).
 * Persisted as lowercase string via {@link DataExportStatusConverter}.
 */
public enum DataExportStatus {
    PENDING, PROCESSING, READY, FAILED
}
