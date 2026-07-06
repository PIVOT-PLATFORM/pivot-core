package fr.pivot.modules.api;

import fr.pivot.auth.entity.User;
import fr.pivot.auth.service.AuditService;
import fr.pivot.config.CookieHelper;
import fr.pivot.tenant.api.TenantNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller de gestion des overrides de module par tenant — {@code POST}/{@code DELETE}
 * {@code /api/superadmin/tenants/{tenantId}/modules/{moduleId}/override} (US03.3.2 « SUPER_ADMIN
 * active/désactive un module par tenant (override) »).
 *
 * <p>Mapping {@code /superadmin/tenants/{tenantId}/modules/{moduleId}/override} (sans préfixe
 * {@code /api}, ajouté par {@code server.servlet.context-path=/api}) — regroupé sous l'espace
 * d'URL {@code /superadmin/tenants/...} déjà utilisé par {@code SuperAdminTenantController},
 * puisqu'il s'agit d'une action plateforme ciblant un tenant précis.
 *
 * <p>Aucune logique métier ici — délégation intégrale à {@link ModuleOverrideService}, qui porte
 * le {@code @PreAuthorize("hasRole('SUPER_ADMIN')")} (RBAC porté par le service, même motif que
 * {@code SuperAdminTenantController}/{@code AdminModuleController}).
 *
 * <p><strong>Isolation :</strong> {@code ROLE_SUPER_ADMIN} est un rôle plateforme, cross-tenant
 * par conception (voir CLAUDE.md) — {@code tenantId}/{@code moduleId} proviennent exclusivement
 * des {@code @PathVariable} (prévention IDOR, même discipline que
 * {@code SuperAdminTenantController#updateStatus}). Seul l'identifiant du super admin appelant
 * est extrait du contexte de sécurité (audit — {@code superAdminId}).
 */
@RestController
@RequestMapping("/superadmin/tenants/{tenantId}/modules/{moduleId}/override")
public class SuperAdminModuleOverrideController {

    private static final Logger LOG = LoggerFactory.getLogger(SuperAdminModuleOverrideController.class);
    private static final String KEY_ERROR = "error";
    private static final String KEY_MESSAGE = "message";

    private final ModuleOverrideService moduleOverrideService;
    private final AuditService auditService;
    private final CookieHelper cookieHelper;

    /**
     * Construit le contrôleur avec ses collaborateurs.
     *
     * @param moduleOverrideService service de pose/retrait des overrides réservé aux super admins
     * @param auditService          journal d'audit applicatif
     * @param cookieHelper          résolution de l'IP client
     */
    public SuperAdminModuleOverrideController(
            final ModuleOverrideService moduleOverrideService,
            final AuditService auditService,
            final CookieHelper cookieHelper) {
        this.moduleOverrideService = moduleOverrideService;
        this.auditService = auditService;
        this.cookieHelper = cookieHelper;
    }

    /**
     * Pose ou remplace un override forçant l'état d'un module pour un tenant — enregistre
     * l'audit event {@code ModuleOverrideSet} avec l'identifiant du super admin appelant.
     *
     * @param tenantId identifiant du tenant ciblé
     * @param moduleId identifiant technique du module à forcer
     * @param request     payload validé — {@code { "enabled": true|false } }
     * @param httpRequest requête HTTP (résolution IP/User-Agent pour l'audit)
     * @return {@code 200} avec {@link ModuleOverrideResponse} · {@code 401} si le contexte
     *     d'authentification est invalide · {@code 403} si l'appelant n'a pas
     *     {@code ROLE_SUPER_ADMIN} · {@code 404} si {@code tenantId} ou {@code moduleId}
     *     n'existe pas
     */
    @PostMapping
    public ResponseEntity<ModuleOverrideResponse> setOverride(
            @PathVariable("tenantId") final Long tenantId,
            @PathVariable("moduleId") final String moduleId,
            @Valid @RequestBody final SetModuleOverrideRequest request,
            final HttpServletRequest httpRequest) {
        final User actor = resolveActor();
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        final ModuleOverrideResult result =
                moduleOverrideService.setOverride(tenantId, moduleId, request.enabled());

        auditService.log(actor, result.tenant(), AuditService.MODULE_OVERRIDE_SET,
                cookieHelper.clientIp(httpRequest), httpRequest.getHeader("User-Agent"),
                "{\"tenantId\":" + tenantId + ",\"moduleId\":\"" + sanitizeForLog(moduleId)
                        + "\",\"enabled\":" + request.enabled() + ",\"superAdminId\":" + actor.getId() + "}");

        if (LOG.isInfoEnabled()) {
            LOG.info("event=SUPERADMIN_MODULE_OVERRIDE_SET superAdminId={} tenantId={} moduleId={} enabled={}",
                    actor.getId(), tenantId, sanitizeForLog(moduleId), request.enabled());
        }
        return ResponseEntity.ok(ModuleOverrideResponse.from(result));
    }

    /**
     * Retire l'override d'un couple (tenant, module) — le module revient au comportement porté
     * par le choix de l'admin du tenant. Enregistre l'audit event {@code ModuleOverrideRemoved}
     * avec l'identifiant du super admin appelant.
     *
     * @param tenantId identifiant du tenant ciblé
     * @param moduleId identifiant technique du module dont l'override doit être retiré
     * @param request  requête HTTP (résolution IP/User-Agent pour l'audit)
     * @return {@code 200} avec {@link ModuleOverrideResponse} (idempotent : {@code 200} même si
     *     aucun override n'existait) · {@code 401} si le contexte d'authentification est
     *     invalide · {@code 403} si l'appelant n'a pas {@code ROLE_SUPER_ADMIN} · {@code 404} si
     *     {@code tenantId} ou {@code moduleId} n'existe pas
     */
    @DeleteMapping
    public ResponseEntity<ModuleOverrideResponse> removeOverride(
            @PathVariable("tenantId") final Long tenantId,
            @PathVariable("moduleId") final String moduleId,
            final HttpServletRequest request) {
        final User actor = resolveActor();
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        final ModuleOverrideResult result = moduleOverrideService.removeOverride(tenantId, moduleId);

        auditService.log(actor, result.tenant(), AuditService.MODULE_OVERRIDE_REMOVED,
                cookieHelper.clientIp(request), request.getHeader("User-Agent"),
                "{\"tenantId\":" + tenantId + ",\"moduleId\":\"" + sanitizeForLog(moduleId)
                        + "\",\"superAdminId\":" + actor.getId() + "}");

        if (LOG.isInfoEnabled()) {
            LOG.info("event=SUPERADMIN_MODULE_OVERRIDE_REMOVED superAdminId={} tenantId={} moduleId={}",
                    actor.getId(), tenantId, sanitizeForLog(moduleId));
        }
        return ResponseEntity.ok(ModuleOverrideResponse.from(result));
    }

    // ----------------------------------------------------------------
    // Exception handling — local à ce contrôleur (pas de handler global)
    // ----------------------------------------------------------------

    /**
     * Traduit un {@code tenantId} inexistant en {@code 404 Not Found} — même contrat que
     * {@code SuperAdminTenantController}.
     *
     * @return corps d'erreur {@code 404}
     */
    @ExceptionHandler(TenantNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleTenantNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                KEY_ERROR, "TENANT_NOT_FOUND",
                KEY_MESSAGE, "Ce tenant n'existe pas"));
    }

    // Module hors registre (fr.pivot.core.modules.UnknownModuleException) : déjà traduit en
    // 404 MODULE_NOT_FOUND par GlobalExceptionHandler, pas de handler local nécessaire ici.

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    /**
     * Neutralise les caractères de contrôle CR/LF d'une valeur avant de la loguer.
     *
     * <p>{@code moduleId} provient d'un {@code @PathVariable} — donnée utilisateur non fiable.
     * Sans neutralisation, un identifiant contenant {@code \r} ou {@code \n} permettrait
     * d'injecter de fausses lignes de log (CWE-117 / log forging) dans un fichier de log en
     * texte brut. La valeur n'est jamais utilisée ailleurs que dans un message de log — la
     * logique métier continue d'utiliser l'identifiant d'origine.
     *
     * @param value valeur potentiellement non fiable à journaliser
     * @return valeur sans retour chariot ni saut de ligne, sûre pour un message de log
     */
    private static String sanitizeForLog(final String value) {
        return value == null ? "null" : value.replaceAll("[\r\n]", "_");
    }

    /**
     * Résout le super admin authentifié depuis le contexte de sécurité — utilisé uniquement
     * pour l'audit ({@code superAdminId}), le RBAC lui-même étant porté par
     * {@link ModuleOverrideService}.
     *
     * @return l'utilisateur authentifié, ou {@code null} si le contexte d'authentification est
     *     invalide
     */
    private User resolveActor() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !(auth.getDetails() instanceof User user)) {
            LOG.warn("event=SUPERADMIN_MODULE_OVERRIDE_REJECTED reason=invalid_auth_details type={}",
                    auth == null || auth.getDetails() == null ? "null" : auth.getDetails().getClass().getName());
            return null;
        }

        return user;
    }
}
