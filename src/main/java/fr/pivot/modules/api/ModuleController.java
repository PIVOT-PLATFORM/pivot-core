package fr.pivot.modules.api;

import fr.pivot.auth.entity.User;
import fr.pivot.core.modules.ModuleActivationService;
import fr.pivot.core.modules.ModuleRegistry;
import fr.pivot.core.modules.UnknownModuleException;
import fr.pivot.modules.registry.ModuleDto;
import fr.pivot.modules.registry.ModuleRegistryService;
import fr.pivot.modules.registry.ModuleStatusDto;
import fr.pivot.core.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller exposant le registre des modules PIVOT.
 *
 * <p>Responsabilité unique : résolution du contexte tenant depuis le
 * {@link SecurityContextHolder} et délégation au {@link ModuleRegistryService}
 * (liste des modules) ou au {@link ModuleActivationService} (statut d'un module précis,
 * source de vérité pour le {@code moduleGuard} Angular). Aucune logique métier dans ce
 * contrôleur.
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
    private final ModuleRegistry moduleRegistry;
    private final ModuleActivationService moduleActivationService;

    /**
     * Construit le contrôleur avec ses collaborateurs de service.
     *
     * @param moduleRegistryService    service de résolution des modules par tenant
     * @param moduleRegistry           registre des modules enregistrés (vérification d'existence)
     * @param moduleActivationService  service de résolution du statut d'activation par tenant
     */
    public ModuleController(final ModuleRegistryService moduleRegistryService,
                             final ModuleRegistry moduleRegistry,
                             final ModuleActivationService moduleActivationService) {
        this.moduleRegistryService = moduleRegistryService;
        this.moduleRegistry = moduleRegistry;
        this.moduleActivationService = moduleActivationService;
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

    /**
     * Retourne le statut d'activation d'un module précis pour le tenant courant.
     *
     * <p>Consommé par le {@code moduleGuard} Angular avant toute navigation vers une route
     * d'un module — doit répondre avant que le bundle lazy-loaded du module ne soit chargé.
     *
     * <p><b>Sémantique HTTP</b> (voir {@link ModuleStatusDto} pour la justification complète) :
     * <ul>
     *   <li>module inconnu du {@link ModuleRegistry} → 404 (via
     *       {@link UnknownModuleException}, traduit par le gestionnaire d'exceptions global) ;</li>
     *   <li>module connu, désactivé pour le tenant → 200 {@code {"enabled": false}} ;</li>
     *   <li>module connu, activé pour le tenant → 200 {@code {"enabled": true}}.</li>
     * </ul>
     * Aucune mise en cache HTTP côté client : {@code Cache-Control: no-store}, pour que le
     * guard obtienne systématiquement l'état courant (le cache applicatif Redis côté serveur
     * est un sujet distinct, hors périmètre de cet endpoint — voir EN03.3).
     *
     * @param id identifiant technique du module (ex. {@code "whiteboard"})
     * @return 200 avec le {@link ModuleStatusDto}, 401 si le contexte d'authentification est
     *         invalide, 404 si le module n'est pas enregistré dans le registre
     */
    @GetMapping("/{id}/status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ModuleStatusDto> getModuleStatus(@PathVariable("id") final String id) {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (!(auth.getDetails() instanceof User user)) {
            LOG.warn("event=GET_MODULE_STATUS_REJECTED reason=invalid_auth_details moduleId={} type={}",
                    id, auth.getDetails() == null ? "null" : auth.getDetails().getClass().getName());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (!moduleRegistry.isRegistered(id)) {
            LOG.warn("event=GET_MODULE_STATUS_REJECTED reason=unknown_module moduleId={} userId={}",
                    id, user.getId());
            throw new UnknownModuleException(id);
        }

        final Long tenantId = user.getTenant() != null ? user.getTenant().getId() : null;
        final boolean enabled = moduleActivationService.isEnabled(tenantId, id);

        LOG.info("event=GET_MODULE_STATUS userId={} tenantId={} moduleId={} enabled={}",
                user.getId(), tenantId, id, enabled);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ModuleStatusDto(enabled));
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
