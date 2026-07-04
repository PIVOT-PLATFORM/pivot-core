package fr.pivot.tenant.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller de supervision plateforme des tenants — {@code GET /api/superadmin/tenants}
 * (US06.2.3 « Super admin liste tous les tenants »).
 *
 * <p>Mapping {@code /superadmin/tenants} (sans préfixe {@code /api}) car
 * {@code server.servlet.context-path=/api} est déjà configuré globalement
 * (voir {@code application.yml}) — le chemin externe résolu est bien
 * {@code /api/superadmin/tenants}.
 *
 * <p>Aucune logique métier ici — délégation intégrale à {@link SuperAdminTenantService}, qui
 * porte le {@code @PreAuthorize("hasRole('SUPER_ADMIN')")} (RBAC porté par le service, même
 * motif que {@code AdminModuleActivationService} pour {@code activate}/{@code deactivate}).
 *
 * <p><strong>Isolation :</strong> cet endpoint est volontairement cross-tenant — il n'extrait
 * ni n'applique aucun {@code TenantContext} (voir CLAUDE.md : {@code ROLE_SUPER_ADMIN} est un
 * rôle plateforme, distinct de {@code ROLE_ADMIN} qui est cantonné au tenant courant).
 */
@RestController
@RequestMapping("/superadmin/tenants")
public class SuperAdminTenantController {

    private final SuperAdminTenantService superAdminTenantService;

    /**
     * Construit le contrôleur avec son service.
     *
     * @param superAdminTenantService service de supervision des tenants
     */
    public SuperAdminTenantController(final SuperAdminTenantService superAdminTenantService) {
        this.superAdminTenantService = superAdminTenantService;
    }

    /**
     * Liste paginée et filtrée de tous les tenants de la plateforme.
     *
     * @param name     filtre optionnel — sous-chaîne du nom (paramètre {@code name})
     * @param isActive filtre optionnel — statut actif/inactif (paramètre {@code is_active})
     * @param plan     filtre optionnel — plan exact (paramètre {@code plan})
     * @param authMode filtre optionnel — mode d'authentification exact (paramètre {@code auth_mode})
     * @param pageable pagination — défaut {@code page=0}, {@code size=20}, tri {@code createdAt DESC}
     * @return {@code 200} avec l'enveloppe {@link TenantPageResponse} · {@code 403} si l'appelant
     *     n'a pas {@code ROLE_SUPER_ADMIN}
     */
    @GetMapping
    public ResponseEntity<TenantPageResponse> list(
            @RequestParam(name = "name", required = false) final String name,
            @RequestParam(name = "is_active", required = false) final Boolean isActive,
            @RequestParam(name = "plan", required = false) final String plan,
            @RequestParam(name = "auth_mode", required = false) final String authMode,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) final Pageable pageable) {
        final Page<TenantSummaryDto> page =
                superAdminTenantService.listTenants(name, isActive, plan, authMode, pageable);
        return ResponseEntity.ok(TenantPageResponse.from(page));
    }
}
