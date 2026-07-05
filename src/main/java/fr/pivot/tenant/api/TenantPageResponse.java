package fr.pivot.tenant.api;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Enveloppe JSON de pagination pour {@code GET /api/superadmin/tenants} — US06.2.3.
 *
 * <p>Reproduit explicitement le contrat exigé par l'AC (forme JSON classique de
 * {@code org.springframework.data.domain.Page} : {@code content, totalElements, totalPages,
 * number, size}) plutôt que de laisser Spring sérialiser un {@link Page} directement en
 * réponse de contrôleur. Ce choix isole le contrat HTTP de tout changement futur de la
 * sérialisation Jackson par défaut de Spring Data (dépréciation du retour direct de
 * {@code Page} en faveur de {@code PagedModel}, dont la forme JSON — objet {@code page}
 * imbriqué — ne correspond pas à l'AC).
 *
 * @param content       éléments de la page courante
 * @param totalElements nombre total d'éléments toutes pages confondues
 * @param totalPages    nombre total de pages
 * @param number        index de la page courante (0-based)
 * @param size          taille de page demandée
 */
public record TenantPageResponse(
        List<TenantSummaryDto> content,
        long totalElements,
        int totalPages,
        int number,
        int size) {

    /**
     * Copie défensive de {@code content} — évite qu'une liste mutable externe soit stockée
     * (SpotBugs EI_EXPOSE_REP2) ou que la liste interne soit exposée en écriture (EI_EXPOSE_REP).
     *
     * @param content       éléments de la page courante
     * @param totalElements nombre total d'éléments toutes pages confondues
     * @param totalPages    nombre total de pages
     * @param number        index de la page courante (0-based)
     * @param size          taille de page demandée
     */
    public TenantPageResponse {
        content = List.copyOf(content);
    }

    /**
     * Construit l'enveloppe à partir d'une {@link Page} Spring Data de {@link TenantSummaryDto}.
     *
     * @param page page source
     * @return enveloppe JSON conforme au contrat AC
     */
    public static TenantPageResponse from(final Page<TenantSummaryDto> page) {
        return new TenantPageResponse(
                page.getContent(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.getNumber(),
                page.getSize());
    }
}
