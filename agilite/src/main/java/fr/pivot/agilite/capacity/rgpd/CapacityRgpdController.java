package fr.pivot.agilite.capacity.rgpd;

import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.web.AgiliteApiPaths;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the capacity module's data-subject rights (US11.8.1, RGPD Art.
 * 15/17/20) — access/portability and erasure, scoped to the {@link
 * fr.pivot.agilite.capacity.CapacityAbsence} rows this module owns.
 *
 * <p>Full path (including the application context) is {@code /api/agilite/capacity/rgpd}. Every
 * endpoint requires a valid {@code Authorization: Bearer <token>} header, resolved into a {@link
 * RequestPrincipal} by {@code RequestPrincipalResolver} (EN08.3) — same authentication convention
 * as every other capacity controller.
 */
@RestController
@RequestMapping(AgiliteApiPaths.BASE + "/capacity/rgpd")
public class CapacityRgpdController {

    private final CapacityRgpdService rgpdService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param rgpdService the data-subject rights business logic service
     */
    public CapacityRgpdController(final CapacityRgpdService rgpdService) {
        this.rgpdService = rgpdService;
    }

    /**
     * Exports every capacity absence period recorded for one person, across every capacity event
     * of the team the caller shares with them — right of access/portability (RGPD Art. 15/20).
     *
     * @param teamMemberRef the data subject's {@code public.team_members.id}
     * @param principal     the resolved caller identity
     * @return the data subject's absence periods
     */
    @GetMapping("/members/{teamMemberRef}/data")
    public CapacityRgpdExportResponse exportData(
            @PathVariable final Long teamMemberRef,
            final RequestPrincipal principal) {
        return rgpdService.exportData(teamMemberRef, principal.userId(), principal.tenantId());
    }

    /**
     * Erases every capacity absence period recorded for one person, across every capacity event
     * of the team the caller shares with them — right to erasure (RGPD Art. 17). Restricted to
     * callers holding a write-capable role in that team.
     *
     * @param teamMemberRef the data subject's {@code public.team_members.id}
     * @param principal     the resolved caller identity
     */
    @DeleteMapping("/members/{teamMemberRef}/data")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eraseData(
            @PathVariable final Long teamMemberRef,
            final RequestPrincipal principal) {
        rgpdService.eraseData(teamMemberRef, principal.userId(), principal.tenantId());
    }
}
