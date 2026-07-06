package fr.pivot.plan.api;

import fr.pivot.plan.entity.Plan;

import java.time.Instant;
import java.util.List;

/**
 * DTO représentant un plan commercial/tarifaire pour les écrans de configuration super-admin
 * (US03.3.1). Jamais l'entité {@link Plan} exposée directement en API (règle absolue CLAUDE.md).
 *
 * <p>{@code moduleIds} est trié alphabétiquement pour une réponse déterministe (l'entité stocke
 * un {@code Set} non ordonné) — confortable côté tests et côté client Angular consommateur.
 *
 * @param id        identifiant technique du plan
 * @param name      nom du plan
 * @param moduleIds identifiants des modules bundlés dans ce plan, triés alphabétiquement
 * @param createdAt date de création du plan
 */
public record PlanDto(Long id, String name, List<String> moduleIds, Instant createdAt) {

    /**
     * Construit le DTO à partir de l'entité {@link Plan}.
     *
     * @param plan entité plan source
     * @return DTO du plan, avec sa liste de modules triée
     */
    public static PlanDto from(final Plan plan) {
        return new PlanDto(
                plan.getId(),
                plan.getName(),
                plan.getModuleIds().stream().sorted().toList(),
                plan.getCreatedAt());
    }
}
