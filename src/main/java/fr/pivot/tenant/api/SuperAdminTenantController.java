package fr.pivot.tenant.api;

import fr.pivot.auth.entity.User;
import fr.pivot.auth.service.AuditService;
import fr.pivot.config.CookieHelper;
import fr.pivot.tenant.entity.Tenant;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller de gestion plateforme des tenants — {@code GET /api/superadmin/tenants}
 * (US06.2.3 « Super admin liste tous les tenants »), {@code POST /api/superadmin/tenants} et
 * {@code GET /api/superadmin/tenants/check-slug} (US06.2.1 « Super admin crée un tenant ») et
 * {@code PATCH /api/superadmin/tenants/{tenantId}/status} (US06.2.2 « Super admin désactive un
 * tenant »).
 *
 * <p>Mapping {@code /superadmin/tenants} (sans préfixe {@code /api}, ajouté par {@code
 * server.servlet.context-path=/api}).
 *
 * <p>Aucune logique métier ici — délégation intégrale à {@link SuperAdminTenantService}, qui
 * porte le {@code @PreAuthorize("hasRole('SUPER_ADMIN')")} (RBAC porté par le service, même
 * motif que {@code AdminModuleActivationService} pour {@code activate}/{@code deactivate}).
 *
 * <p><strong>Isolation :</strong> ce contrôleur est volontairement cross-tenant — il n'extrait
 * ni n'applique aucun {@code TenantContext} (voir CLAUDE.md : {@code ROLE_SUPER_ADMIN} est un
 * rôle plateforme, distinct de {@code ROLE_ADMIN} qui est cantonné au tenant courant). Le seul
 * identifiant extrait du contexte de sécurité est celui du super admin appelant (pour le rate
 * limiting et l'audit) — jamais un {@code tenantId} accepté depuis le corps de la requête. Pour
 * {@code updateStatus}, le {@code tenantId} ciblé provient exclusivement du
 * {@code @PathVariable} — jamais du corps de requête ni d'un en-tête. Le corps ne porte que le
 * {@code status} demandé.
 */
@RestController
@RequestMapping("/superadmin/tenants")
public class SuperAdminTenantController {

    private static final Logger LOG = LoggerFactory.getLogger(SuperAdminTenantController.class);
    private static final String KEY_ERROR = "error";
    private static final String KEY_MESSAGE = "message";

    private final SuperAdminTenantService superAdminTenantService;
    private final AuditService auditService;
    private final CookieHelper cookieHelper;

    /**
     * Construit le contrôleur avec ses collaborateurs.
     *
     * @param superAdminTenantService service de supervision/création/vérification/désactivation des tenants
     * @param auditService            journal d'audit applicatif
     * @param cookieHelper            résolution de l'IP client, partagée avec les autres contrôleurs
     */
    public SuperAdminTenantController(
            final SuperAdminTenantService superAdminTenantService,
            final AuditService auditService,
            final CookieHelper cookieHelper) {
        this.superAdminTenantService = superAdminTenantService;
        this.auditService = auditService;
        this.cookieHelper = cookieHelper;
    }

    /**
     * Liste paginée et filtrée de tous les tenants de la plateforme.
     *
     * @param name     filtre optionnel — sous-chaîne du nom (paramètre {@code name})
     * @param isActive filtre optionnel — statut actif/inactif (paramètre {@code is_active})
     * @param plan     filtre optionnel — plan exact (paramètre {@code plan})
     * @param authMode filtre optionnel — mode d'authentification exact (paramètre {@code auth_mode})
     * @param pageable pagination — défaut {@code page=0}, {@code size=20}, tri {@code createdAt DESC}
     *     · {@code size} plafonné globalement par {@link fr.pivot.config.PaginationConfig}
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

    /**
     * Crée un nouveau tenant sur la plateforme.
     *
     * @param request payload validé (nom, slug, plan, mode d'authentification)
     * @param httpRequest requête HTTP (résolution IP/User-Agent pour le rate limiting et l'audit)
     * @return {@code 201} avec l'ID du tenant créé, son slug et son URL d'invitation ·
     *     {@code 400} si le payload est invalide (champ manquant, slug mal formé, plan/auth_mode
     *     hors liste) · {@code 401} si le contexte d'authentification est invalide ·
     *     {@code 403} si l'appelant n'a pas {@code ROLE_SUPER_ADMIN} · {@code 409} si le slug
     *     est déjà pris · {@code 422} si le slug est un terme réservé · {@code 429} si le
     *     compte a dépassé 10 créations dans l'heure
     */
    @PostMapping
    public ResponseEntity<CreateTenantResponse> create(
            @Valid @RequestBody final CreateTenantRequest request, final HttpServletRequest httpRequest) {
        final User caller = resolveCaller();
        if (caller == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        final CreateTenantResponse response = superAdminTenantService.createTenant(
                request, caller, cookieHelper.clientIp(httpRequest), httpRequest.getHeader("User-Agent"));

        if (LOG.isInfoEnabled()) {
            LOG.info("event=SUPERADMIN_TENANT_CREATED superAdminId={} tenantId={} slug={}",
                    caller.getId(), response.id(), sanitizeForLog(response.slug()));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Vérifie la disponibilité d'un slug en temps réel (debounce 500ms côté Angular).
     *
     * @param slug slug candidat
     * @return {@code 200} avec {@link SlugAvailabilityResponse} · {@code 401}/{@code 403} comme
     *     ci-dessus. Ne renvoie jamais {@code 409}/{@code 422} : l'indisponibilité est portée
     *     dans le corps de la réponse, pas dans le code HTTP, puisqu'il s'agit d'une simple
     *     lecture, pas d'une tentative de création.
     */
    @GetMapping("/check-slug")
    public ResponseEntity<SlugAvailabilityResponse> checkSlug(
            @RequestParam(name = "slug", required = false) final String slug) {
        final User caller = resolveCaller();
        if (caller == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(superAdminTenantService.checkSlugAvailability(slug));
    }

    /**
     * Désactive un tenant : révoque en masse (O(1)) les sessions de tous ses utilisateurs et
     * enregistre l'audit event {@code TenantDeactivated}.
     *
     * @param tenantId    identifiant du tenant ciblé (path variable — jamais le corps)
     * @param request     corps de requête — {@code { "status": "INACTIVE" } }
     * @param httpRequest requête HTTP (résolution IP/User-Agent pour l'audit)
     * @return {@code 200} avec {@link TenantStatusResponse} une fois la révocation bulk
     *     confirmée en base · {@code 401} si le contexte d'authentification est invalide ·
     *     {@code 403} si l'appelant n'a pas {@code ROLE_SUPER_ADMIN}, ou si {@code tenantId}
     *     désigne le tenant système · {@code 404} si {@code tenantId} n'existe pas ·
     *     {@code 400} si {@code status} n'est pas {@code "INACTIVE"}
     */
    @PatchMapping("/{tenantId}/status")
    public ResponseEntity<TenantStatusResponse> updateStatus(
            @PathVariable("tenantId") final Long tenantId,
            @Valid @RequestBody final TenantStatusRequest request,
            final HttpServletRequest httpRequest) {

        final User actor = resolveActor();
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        final Tenant tenant = superAdminTenantService.updateStatus(tenantId, request.status());

        auditService.log(actor, tenant, AuditService.TENANT_DEACTIVATED,
                cookieHelper.clientIp(httpRequest), httpRequest.getHeader("User-Agent"),
                "{\"tenantId\":" + tenant.getId() + ",\"actorId\":" + actor.getId() + "}");

        LOG.info("event=SUPERADMIN_TENANT_DEACTIVATED tenantId={} actorId={}",
                tenant.getId(), actor.getId());
        return ResponseEntity.ok(new TenantStatusResponse(tenant.getId(), "INACTIVE"));
    }

    // ----------------------------------------------------------------
    // Exception handling — local à ce contrôleur (pas de handler global)
    // ----------------------------------------------------------------

    /**
     * Traduit un slug déjà pris en {@code 409 Conflict}.
     *
     * @return corps d'erreur {@code 409}
     */
    @ExceptionHandler(TenantSlugAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleSlugAlreadyExists() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                KEY_ERROR, "TENANT_SLUG_ALREADY_EXISTS",
                KEY_MESSAGE, "Ce slug est déjà utilisé par un autre tenant"));
    }

    /**
     * Traduit un slug réservé en {@code 422 Unprocessable Entity}.
     *
     * @return corps d'erreur {@code 422}
     */
    @ExceptionHandler(ReservedTenantSlugException.class)
    public ResponseEntity<Map<String, Object>> handleReservedSlug() {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                KEY_ERROR, "TENANT_SLUG_RESERVED",
                KEY_MESSAGE, "Ce slug est réservé et ne peut pas être utilisé"));
    }

    /**
     * Traduit un {@code tenantId} inexistant en {@code 404 Not Found}.
     *
     * @param ex l'exception levée par le service
     * @return corps d'erreur {@code 404}
     */
    @ExceptionHandler(TenantNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleTenantNotFound(final TenantNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                KEY_ERROR, "TENANT_NOT_FOUND",
                KEY_MESSAGE, "Ce tenant n'existe pas"));
    }

    /**
     * Traduit une tentative de désactivation du tenant système en {@code 403 Forbidden} avec
     * un message explicite (US06.2.2, critère de protection du tenant système).
     *
     * @param ex l'exception levée par le service
     * @return corps d'erreur {@code 403}
     */
    @ExceptionHandler(SystemTenantProtectedException.class)
    public ResponseEntity<Map<String, Object>> handleSystemTenantProtected(
            final SystemTenantProtectedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                KEY_ERROR, "SYSTEM_TENANT_PROTECTED",
                KEY_MESSAGE, "Le tenant système hébergeant les comptes super-administrateur "
                        + "ne peut pas être désactivé"));
    }

    /**
     * Traduit une valeur de {@code status} non supportée en {@code 400 Bad Request}.
     *
     * @param ex l'exception levée par le service
     * @return corps d'erreur {@code 400}
     */
    @ExceptionHandler(UnsupportedTenantStatusException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedStatus(
            final UnsupportedTenantStatusException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                KEY_ERROR, "UNSUPPORTED_TENANT_STATUS",
                KEY_MESSAGE, "Seul le statut INACTIVE est actuellement supporté"));
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    /**
     * Neutralise les caractères de contrôle CR/LF d'une valeur avant de la loguer.
     *
     * <p>{@code slug} provient in fine d'un {@code @RequestBody} — donnée utilisateur non
     * fiable. Sans neutralisation, une valeur contenant {@code \r} ou {@code \n} permettrait
     * d'injecter de fausses lignes de log (CWE-117 / log forging).
     *
     * @param value valeur potentiellement non fiable à journaliser
     * @return valeur sans retour chariot ni saut de ligne, sûre pour un message de log
     */
    private static String sanitizeForLog(final String value) {
        return value == null ? "null" : value.replaceAll("[\r\n]", "_");
    }

    /**
     * Résout le super admin authentifié depuis le contexte de sécurité.
     *
     * @return l'utilisateur authentifié, ou {@code null} si le contexte d'authentification est invalide
     */
    private User resolveCaller() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getDetails() instanceof User user)) {
            LOG.warn("event=SUPERADMIN_TENANTS_REJECTED reason=invalid_auth_details type={}",
                    auth == null || auth.getDetails() == null ? "null" : auth.getDetails().getClass().getName());
            return null;
        }
        return user;
    }

    /**
     * Résout l'utilisateur super-admin authentifié depuis le contexte de sécurité — utilisé
     * uniquement pour l'audit ({@code actorId}), le RBAC lui-même étant porté par
     * {@link SuperAdminTenantService#updateStatus}.
     *
     * @return l'utilisateur authentifié, ou {@code null} si le contexte d'authentification
     *     est invalide
     */
    private User resolveActor() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !(auth.getDetails() instanceof User user)) {
            LOG.warn("event=SUPERADMIN_TENANT_STATUS_REJECTED reason=invalid_auth_details type={}",
                    auth == null || auth.getDetails() == null ? "null" : auth.getDetails().getClass().getName());
            return null;
        }

        return user;
    }
}
