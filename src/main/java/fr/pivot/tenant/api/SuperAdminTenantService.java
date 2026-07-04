package fr.pivot.tenant.api;

import fr.pivot.auth.repository.TenantUserCountProjection;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Service de supervision plateforme des tenants — US06.2.3 « Super admin liste tous les
 * tenants ».
 *
 * <p>Portée volontairement cross-tenant : {@code ROLE_SUPER_ADMIN} est un rôle plateforme
 * (voir CLAUDE.md, tableau des rôles), distinct de {@code ROLE_ADMIN} cantonné au tenant
 * courant — {@code TenantContext} (isolation par tenant) ne s'applique pas ici par conception,
 * cet endpoint existe précisément pour parcourir tous les tenants de la plateforme.
 */
@Service
public class SuperAdminTenantService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;

    /**
     * Construit le service avec ses collaborateurs.
     *
     * @param tenantRepository dépôt des tenants
     * @param userRepository   dépôt des utilisateurs (décompte par tenant)
     */
    public SuperAdminTenantService(final TenantRepository tenantRepository, final UserRepository userRepository) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
    }

    /**
     * Liste paginée et filtrée de tous les tenants de la plateforme, avec le nombre
     * d'utilisateurs non supprimés rattachés à chacun.
     *
     * <p><strong>RBAC :</strong> {@code @PreAuthorize("hasRole('SUPER_ADMIN')")} — un appel
     * porté par un rôle inférieur (ex. {@code ROLE_ADMIN}) lève
     * {@link org.springframework.security.access.AccessDeniedException}, traduite en {@code 403}
     * par le comportement par défaut de Spring Security (pas de gestionnaire custom nécessaire).
     *
     * <p>{@code userCount} est calculé par une requête d'agrégation groupée sur la seule page
     * courante ({@link UserRepository#countActiveUsersByTenantIds}) — jamais un compteur
     * dénormalisé sur {@link Tenant}, et jamais de requête N+1 (un seul aller-retour BDD pour
     * l'ensemble des tenants de la page).
     *
     * @param name     filtre optionnel — sous-chaîne du nom, insensible à la casse
     * @param active   filtre optionnel — statut actif/inactif
     * @param plan     filtre optionnel — plan exact
     * @param authMode filtre optionnel — mode d'authentification exact
     * @param pageable pagination et tri (défaut porté par le contrôleur : {@code createdAt DESC}, taille 20)
     * @return page de {@link TenantSummaryDto}
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Page<TenantSummaryDto> listTenants(
            final String name,
            final Boolean active,
            final String plan,
            final String authMode,
            final Pageable pageable) {
        final Page<Tenant> tenants = tenantRepository.findAll(
                TenantSpecifications.filter(name, active, plan, authMode), pageable);

        final List<Long> tenantIds = tenants.getContent().stream().map(Tenant::getId).toList();
        final Map<Long, Long> userCounts = tenantIds.isEmpty()
                ? Map.of()
                : userRepository.countActiveUsersByTenantIds(tenantIds).stream()
                        .collect(Collectors.toMap(
                                TenantUserCountProjection::getTenantId,
                                TenantUserCountProjection::getUserCount));

        return tenants.map(tenant -> TenantSummaryDto.from(tenant, userCounts.getOrDefault(tenant.getId(), 0L)));
    }
}
