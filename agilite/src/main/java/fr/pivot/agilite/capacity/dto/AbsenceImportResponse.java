package fr.pivot.agilite.capacity.dto;

import java.util.List;

/**
 * Response payload for a bulk CSV absence import (US11.7.1) — never all-or-nothing, valid rows
 * are imported even if others fail.
 *
 * @param imported number of rows successfully imported (including silently-deduped exact
 *                 duplicates, per the AC — they count as "imported" without being recreated)
 * @param errors   one entry per failed row
 */
public record AbsenceImportResponse(int imported, List<RowError> errors) {

    /**
     * One failed CSV row.
     *
     * @param line the 1-based line number in the source file (header excluded)
     * @param code the machine-readable error code ({@code UNKNOWN_MEMBER}/{@code INVALID_DATE_RANGE})
     */
    public record RowError(int line, String code) {
    }
}
