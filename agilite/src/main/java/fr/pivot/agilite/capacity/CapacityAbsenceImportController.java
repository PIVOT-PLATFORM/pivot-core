package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.dto.AbsenceImportResponse;
import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.web.AgiliteApiPaths;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * REST controller exposing the generic CSV bulk absence import under {@code
 * /capacity/events/{eventId}/absences/import} (US11.7.1).
 *
 * <p>The full path (including the application context) is {@code
 * /api/agilite/capacity/events/{eventId}/absences/import}.
 */
@RestController
@RequestMapping(AgiliteApiPaths.BASE + "/capacity/events/{eventId}/absences/import")
@Validated
public class CapacityAbsenceImportController {

    private final CapacityAbsenceImportService importService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param importService the CSV import business logic service (US11.7.1)
     */
    public CapacityAbsenceImportController(final CapacityAbsenceImportService importService) {
        this.importService = importService;
    }

    /**
     * Imports absences in bulk from an uploaded CSV file.
     *
     * @param eventId   the event UUID from the path
     * @param file      the uploaded CSV file, {@code multipart/form-data}
     * @param principal the resolved caller identity
     * @return the per-row import result, HTTP 200 even if some rows failed (never all-or-nothing)
     */
    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public AbsenceImportResponse importCsv(
            @PathVariable final UUID eventId,
            @RequestParam("file") final MultipartFile file,
            final RequestPrincipal principal) {
        return importService.importCsv(eventId, file, principal.userId(), principal.tenantId());
    }
}
