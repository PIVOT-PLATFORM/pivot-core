package fr.pivot.modules.api;

import fr.pivot.auth.entity.User;
import fr.pivot.modules.registry.ModuleDto;
import fr.pivot.modules.registry.ModuleRegistryService;
import fr.pivot.core.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller exposant le registre des modules PIVOT.
 *
 * <p>Responsabilité unique : résolution du contexte tenant depuis le
 * {@link SecurityContextHolder} et délégation au {@link ModuleRegistryService}.
 * Aucune logique métier dans ce contrôleur.
 *
 * <p>Le filtre {@link fr.pivot.config.TokenAuthenticationFilter} peuple le contexte de
 * sécurité avec un {@code UsernamePasswordAuthenticationToken} dont les détails
 * ({@code getDetails()}) contiennent l'entité {@link User} résolue depuis la BDD.
 */
@RestController
@RequestMapping("/api/modules")
public class ModuleController {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleController.class);

    private final ModuleRegistryService moduleRegistryService;

    /**
     * Construit le contrôleur avec son collaborateur de service.
     *
     * @param moduleRegistryService service de résolution des modules par tenant
     */
    public ModuleController(final ModuleRegistryService moduleRegistryService) {
        this.moduleRegistryService = moduleRegistryService;
    }

    /**
     * Retourne la liste des modules PIVOT avec leur statut pour l'utilisateur authentifié.
     *
     * <p>Le contexte tenant est construit depuis le {@link User} stocké dans les détails
     * de l'authentication courante (posé par {@link fr.pivot.config.TokenAuthenticationFilter}).
     * Si les détails ne sont pas de type {@link User} (chemin OIDC enterprise ou contexte
     * inattendu), la requête est rejetée avec 401.
     *
     * @return 200 OK avec la liste des {@link ModuleDto}, ou 401 si le contexte est invalide
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ModuleDto>> getModules() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (!(auth.getDetails() instanceof User user)) {
            LOG.warn("event=GET_MODULES_REJECTED reason=invalid_auth_details type={}",
                    auth.getDetails() == null ? "null" : auth.getDetails().getClass().getName());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        final TenantContext ctx = buildTenantContext(user);

        LOG.info("event=GET_MODULES userId={} tenantId={} role={}",
                user.getId(), ctx.tenantId(), user.getRole());

        final List<ModuleDto> modules = moduleRegistryService.getModulesForTenant(ctx);
        return ResponseEntity.ok(modules);
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    /**
     * Construit un {@link TenantContext} depuis l'entité {@link User} authentifiée.
     *
     * <p>{@code tenantId} est transmis tel quel — même type ({@code Long}, clé primaire
     * {@code public.tenants.id}) que celui utilisé partout ailleurs dans la couche
     * persistance (voir {@link fr.pivot.core.modules.ModuleActivationService}).
     * {@code null} si l'utilisateur n'a pas de tenant.
     *
     * @param user l'utilisateur authentifié résolu par le filtre de sécurité
     * @return contexte tenant prêt pour l'évaluation des modules
     */
    static TenantContext buildTenantContext(final User user) {
        final Long tenantId = user.getTenant() != null ? user.getTenant().getId() : null;
        final String userId = user.getId() != null ? user.getId().toString() : "unknown";
        return new TenantContext(tenantId, userId, user.getRole());
    }
}
