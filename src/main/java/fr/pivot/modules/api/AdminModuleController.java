package fr.pivot.modules.api;

import fr.pivot.auth.entity.User;
import fr.pivot.auth.service.AuditService;
import fr.pivot.config.CookieHelper;
import fr.pivot.core.modules.ModuleActivationService;
import fr.pivot.core.modules.ModuleRegistry;
import fr.pivot.core.modules.PivotModule;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST controller d'administration des modules PIVOT — activation/désactivation par tenant
 * (US03.1.1 « Admin active un module », US03.1.2 « Admin désactive un module »).
 *
 * <p>Responsabilité unique : résolution du contexte tenant/utilisateur depuis le
 * {@link SecurityContextHolder} (même schéma que {@link ModuleController}, mais avec un
 * identifiant tenant {@code Long} brut — {@link AdminModuleActivationService} et
 * {@link ModuleActivationService} consomment directement {@code Long}, pas
 * {@link fr.pivot.core.tenant.TenantContext}), délégation à
 * {@link AdminModuleActivationService}, et traduction des exceptions métier en réponses HTTP.
 * Aucune logique métier dans ce contrôleur.
 *
 * <p><strong>Isolation tenant :</strong> le {@code tenantId} n'est jamais accepté depuis le
 * corps de requête, un paramètre de requête ou un en-tête — uniquement depuis l'entité
 * {@link User} posée par {@link fr.pivot.config.TokenAuthenticationFilter} dans les détails
 * de l'authentification courante.
 *
 * <p><strong>RBAC :</strong> l'autorisation {@code ROLE_ADMIN} est portée par
 * {@link AdminModuleActivationService} ({@code @PreAuthorize}), pas par ce contrôleur — un
 * appel non autorisé lève {@link org.springframework.security.access.AccessDeniedException},
 * traduite en {@code 403} par le comportement par défaut de Spring Security (pas de
 * gestionnaire custom nécessaire ici).
 */
@RestController
@RequestMapping("/api/admin/modules")
public class AdminModuleController {

    private static final Logger LOG = LoggerFactory.getLogger(AdminModuleController.class);

    private final AdminModuleActivationService adminModuleActivationService;
    private final ModuleActivationService moduleActivationService;
    private final ModuleRegistry moduleRegistry;
    private final AuditService auditService;
    private final CookieHelper cookieHelper;

    /**
     * Construit le contrôleur avec ses collaborateurs.
     *
     * @param adminModuleActivationService service d'activation réservé aux administrateurs
     * @param moduleActivationService      service partagé de lecture d'état d'activation (listing)
     * @param moduleRegistry               registre des modules disponibles (listing)
     * @param auditService                 journal d'audit applicatif
     * @param cookieHelper                 résolution de l'IP client
     */
    public AdminModuleController(
            final AdminModuleActivationService adminModuleActivationService,
            final ModuleActivationService moduleActivationService,
            final ModuleRegistry moduleRegistry,
            final AuditService auditService,
            final CookieHelper cookieHelper) {
        this.adminModuleActivationService = adminModuleActivationService;
        this.moduleActivationService = moduleActivationService;
        this.moduleRegistry = moduleRegistry;
        this.auditService = auditService;
        this.cookieHelper = cookieHelper;
    }

    /**
     * Active un module pour le tenant de l'administrateur authentifié.
     *
     * @param id      identifiant technique du module à activer
     * @param request requête HTTP (résolution IP/User-Agent pour l'audit)
     * @return {@code 200} avec {@code {id, enabled: true}} · {@code 401} si le contexte
     *     d'authentification est invalide · {@code 403} si le module n'est pas enregistré ·
     *     {@code 409} si le module est déjà activé
     */
    @PostMapping("/{id}/activate")
    public ResponseEntity<Map<String, Object>> activate(
            @PathVariable("id") final String id,
            final HttpServletRequest request) {
        final ResolvedAdmin resolved = resolveAdmin();
        if (resolved == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        adminModuleActivationService.activate(resolved.tenantId(), id);
        auditService.log(resolved.user(), AuditService.MODULE_ACTIVATED,
                cookieHelper.clientIp(request), request.getHeader("User-Agent"));

        LOG.info("event=ADMIN_MODULE_ACTIVATED userId={} tenantId={} moduleId={}",
                resolved.user().getId(), resolved.tenantId(), id);
        return ResponseEntity.ok(Map.of("id", id, "enabled", true));
    }

    /**
     * Désactive un module pour le tenant de l'administrateur authentifié.
     *
     * <p>Idempotent : renvoie {@code 200} même si le module était déjà inactif.
     *
     * @param id      identifiant technique du module à désactiver
     * @param request requête HTTP (résolution IP/User-Agent pour l'audit)
     * @return {@code 200} avec {@code {id, enabled: false}} · {@code 401} si le contexte
     *     d'authentification est invalide · {@code 403} si le module n'est pas enregistré
     */
    @DeleteMapping("/{id}/activate")
    public ResponseEntity<Map<String, Object>> deactivate(
            @PathVariable("id") final String id,
            final HttpServletRequest request) {
        final ResolvedAdmin resolved = resolveAdmin();
        if (resolved == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        adminModuleActivationService.deactivate(resolved.tenantId(), id);
        auditService.log(resolved.user(), AuditService.MODULE_DEACTIVATED,
                cookieHelper.clientIp(request), request.getHeader("User-Agent"));

        LOG.info("event=ADMIN_MODULE_DEACTIVATED userId={} tenantId={} moduleId={}",
                resolved.user().getId(), resolved.tenantId(), id);
        return ResponseEntity.ok(Map.of("id", id, "enabled", false));
    }

    /**
     * Liste les modules PIVOT disponibles avec leur état d'activation pour le tenant de
     * l'administrateur authentifié.
     *
     * <p><strong>Limitation documentée :</strong> {@code description} est toujours vide —
     * voir {@link AdminModuleDto}.
     *
     * @return {@code 200} avec la liste des {@link AdminModuleDto}, ou {@code 401} si le
     *     contexte d'authentification est invalide
     */
    @GetMapping
    public ResponseEntity<List<AdminModuleDto>> list() {
        final ResolvedAdmin resolved = resolveAdmin();
        if (resolved == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        final List<AdminModuleDto> modules = moduleRegistry.getModules().stream()
                .map(module -> toAdminDto(module, resolved.tenantId()))
                .toList();
        return ResponseEntity.ok(modules);
    }

    // ----------------------------------------------------------------
    // Exception handling — local à ce contrôleur (pas de handler global)
    // ----------------------------------------------------------------

    /**
     * Traduit une activation redondante en {@code 409 Conflict}.
     *
     * @param ex l'exception levée par le service
     * @return corps d'erreur {@code 409}
     */
    @ExceptionHandler(ModuleAlreadyActiveException.class)
    public ResponseEntity<Map<String, Object>> handleAlreadyActive(final ModuleAlreadyActiveException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "MODULE_ALREADY_ACTIVE",
                "message", "Ce module est déjà activé pour votre organisation"));
    }

    /**
     * Traduit un module hors registre en {@code 403 Forbidden} — voir
     * {@link ModuleNotInPlanException} pour la simplification « plan/entitlement » documentée.
     *
     * @param ex l'exception levée par le service
     * @return corps d'erreur {@code 403}
     */
    @ExceptionHandler(ModuleNotInPlanException.class)
    public ResponseEntity<Map<String, Object>> handleNotInPlan(final ModuleNotInPlanException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", "MODULE_NOT_IN_PLAN",
                "message", "Ce module n'est pas inclus dans votre plan"));
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    private AdminModuleDto toAdminDto(final PivotModule module, final Long tenantId) {
        final boolean enabled = moduleActivationService.isEnabled(tenantId, module.getId());
        return new AdminModuleDto(module.getId(), module.getName(), enabled, "");
    }

    /**
     * Résout l'administrateur authentifié et son tenant depuis le contexte de sécurité.
     *
     * <p>Le {@code tenantId} n'est jamais lu ailleurs que dans l'entité {@link User} posée
     * par le filtre d'authentification — jamais depuis le corps, un paramètre ou un en-tête.
     *
     * @return le couple (utilisateur, tenantId) résolu, ou {@code null} si le contexte
     *     d'authentification est invalide ou si l'utilisateur n'appartient à aucun tenant
     */
    private ResolvedAdmin resolveAdmin() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !(auth.getDetails() instanceof User user)) {
            LOG.warn("event=ADMIN_MODULES_REJECTED reason=invalid_auth_details type={}",
                    auth == null || auth.getDetails() == null ? "null" : auth.getDetails().getClass().getName());
            return null;
        }

        if (user.getTenant() == null) {
            LOG.warn("event=ADMIN_MODULES_REJECTED reason=no_tenant userId={}", user.getId());
            return null;
        }

        return new ResolvedAdmin(user, user.getTenant().getId());
    }

    /**
     * Couple utilisateur authentifié / tenant résolu, interne à ce contrôleur.
     *
     * @param user     utilisateur authentifié
     * @param tenantId identifiant du tenant de l'utilisateur
     */
    private record ResolvedAdmin(User user, Long tenantId) {
    }
}
