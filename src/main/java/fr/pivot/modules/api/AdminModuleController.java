package fr.pivot.modules.api;

import fr.pivot.auth.entity.User;
import fr.pivot.auth.service.AuditService;
import fr.pivot.config.CookieHelper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * (US03.1.1 « Admin active un module », US03.1.2 « Admin désactive un module ») et listing
 * filtré par plan (US03.3.3 « Admin tenant voit uniquement modules de son plan »).
 *
 * <p>Responsabilité unique : résolution du contexte tenant/utilisateur depuis le
 * {@link SecurityContextHolder} (même schéma que {@link ModuleController}, mais avec un
 * identifiant tenant {@code Long} brut — {@link AdminModuleActivationService} et
 * {@link AdminModuleListService} consomment directement {@code Long}, pas
 * {@link fr.pivot.core.tenant.TenantContext}), délégation à ces deux services, et traduction
 * des exceptions métier en réponses HTTP. Aucune logique métier dans ce contrôleur.
 *
 * <p><strong>Isolation tenant :</strong> le {@code tenantId} n'est jamais accepté depuis le
 * corps de requête, un paramètre de requête ou un en-tête — uniquement depuis l'entité
 * {@link User} posée par {@link fr.pivot.config.TokenAuthenticationFilter} dans les détails
 * de l'authentification courante.
 *
 * <p><strong>RBAC :</strong> pour {@code activate}/{@code deactivate}, l'autorisation
 * {@code ROLE_ADMIN} est portée par {@link AdminModuleActivationService} ({@code @PreAuthorize}
 * sur le service, pas sur ce contrôleur). Pour {@code list()}, qui délègue à
 * {@link AdminModuleListService} (pas de {@code @PreAuthorize} porté par ce service partagé —
 * même motif historique que le registre/l'activation partagés avec des endpoints non-admin), le
 * {@code @PreAuthorize("hasRole('ADMIN')")} est porté directement par la méthode du contrôleur.
 * Dans les deux cas, un appel non autorisé lève
 * {@link org.springframework.security.access.AccessDeniedException}, traduite en {@code 403}
 * par le comportement par défaut de Spring Security (pas de gestionnaire custom nécessaire ici).
 */
@RestController
@RequestMapping("/admin/modules")
public class AdminModuleController {

    private static final Logger LOG = LoggerFactory.getLogger(AdminModuleController.class);

    private final AdminModuleActivationService adminModuleActivationService;
    private final AdminModuleListService adminModuleListService;
    private final AuditService auditService;
    private final CookieHelper cookieHelper;

    /**
     * Construit le contrôleur avec ses collaborateurs.
     *
     * @param adminModuleActivationService service d'activation réservé aux administrateurs
     * @param adminModuleListService       résolution de la liste de modules visibles (filtrage
     *                                     par plan + overrides, US03.3.3)
     * @param auditService                 journal d'audit applicatif
     * @param cookieHelper                 résolution de l'IP client
     */
    public AdminModuleController(
            final AdminModuleActivationService adminModuleActivationService,
            final AdminModuleListService adminModuleListService,
            final AuditService auditService,
            final CookieHelper cookieHelper) {
        this.adminModuleActivationService = adminModuleActivationService;
        this.adminModuleListService = adminModuleListService;
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

        if (LOG.isInfoEnabled()) {
            LOG.info("event=ADMIN_MODULE_ACTIVATED userId={} tenantId={} moduleId={}",
                    resolved.user().getId(), resolved.tenantId(), sanitizeForLog(id));
        }
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

        if (LOG.isInfoEnabled()) {
            LOG.info("event=ADMIN_MODULE_DEACTIVATED userId={} tenantId={} moduleId={}",
                    resolved.user().getId(), resolved.tenantId(), sanitizeForLog(id));
        }
        return ResponseEntity.ok(Map.of("id", id, "enabled", false));
    }

    /**
     * Liste les modules PIVOT visibles, avec leur état d'activation, pour le tenant de
     * l'administrateur authentifié.
     *
     * <p><strong>Filtrage par plan (US03.3.3) :</strong> seuls les modules inclus dans le plan
     * commercial du tenant (ou rendus visibles par un override SUPER_ADMIN actif) sont retournés
     * — un module hors plan est simplement absent de la liste, jamais {@code 403}. Voir
     * {@link AdminModuleListService} pour la résolution complète, y compris le champ
     * {@code source} de chaque {@link AdminModuleDto}.
     *
     * <p><strong>Limitation documentée :</strong> {@code description} est toujours vide —
     * voir {@link AdminModuleDto}.
     *
     * @return {@code 200} avec la liste des {@link AdminModuleDto} visibles, ou {@code 401} si
     *     le contexte d'authentification est invalide
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<AdminModuleDto>> list() {
        final ResolvedAdmin resolved = resolveAdmin();
        if (resolved == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return ResponseEntity.ok(adminModuleListService.list(resolved.tenantId()));
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

    /**
     * Neutralise les caractères de contrôle CR/LF d'une valeur avant de la loguer.
     *
     * <p>{@code id} provient d'un {@code @PathVariable} — donnée utilisateur non fiable.
     * Sans neutralisation, un identifiant contenant {@code \r} ou {@code \n} permettrait
     * d'injecter de fausses lignes de log (CWE-117 / log forging) dans un fichier de log
     * en texte brut. La valeur n'est jamais utilisée ailleurs que dans un message de log —
     * la logique métier continue d'utiliser l'identifiant d'origine.
     *
     * @param value valeur potentiellement non fiable à journaliser
     * @return valeur sans retour chariot ni saut de ligne, sûre pour un message de log
     */
    private static String sanitizeForLog(final String value) {
        return value == null ? "null" : value.replaceAll("[\r\n]", "_");
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
