package fr.pivot.tenant.api;

import fr.pivot.auth.entity.User;
import fr.pivot.config.CookieHelper;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller de gestion plateforme des tenants — {@code GET /api/superadmin/tenants}
 * (US06.2.3 « Super admin liste tous les tenants »), {@code POST /api/superadmin/tenants} et
 * {@code GET /api/superadmin/tenants/check-slug} (US06.2.1 « Super admin crée un tenant »).
 *
 * <p>Mapping {@code /superadmin/tenants} (sans préfixe {@code /api}, ajouté par {@code
 * server.servlet.context-path=/api}) — même convention que le contrôleur de US06.2.3 ({@code GET
 * /api/superadmin/tenants}, liste paginée, PR #126, déjà fusionnée ici) et celui de US06.2.2
 * ({@code PATCH /api/superadmin/tenants/{tenantId}/status}, désactivation, PR #135, encore
 * ouverte). Cette classe porte donc pour l'instant {@code list()} + {@code create()}/{@code
 * checkSlug()} — la fusion avec {@code updateStatus()} (PR #135) se réglera en résolution de
 * conflit Git à son intégration, pas dans le code de cette PR. PR #135 crée en outre sa propre
 * migration {@code V4__tenant_invalidation_timestamp.sql}, qui collisionne avec la migration
 * {@code V6__tenant_auth_mode_creation_values.sql} de cette PR (déjà renumérotée depuis {@code
 * V4} au moment de cette fusion) — sa propre renumérotation restera nécessaire à son tour,
 * aucun changement de contrat, juste une réconciliation de fichiers côté mainteneur.
 *
 * <p>Aucune logique métier ici — délégation intégrale à {@link SuperAdminTenantService}, qui
 * porte le {@code @PreAuthorize("hasRole('SUPER_ADMIN')")} (RBAC porté par le service, même
 * motif que {@code AdminModuleActivationService} pour {@code activate}/{@code deactivate}).
 *
 * <p><strong>Isolation :</strong> ce contrôleur est volontairement cross-tenant — il n'extrait
 * ni n'applique aucun {@code TenantContext} (voir CLAUDE.md : {@code ROLE_SUPER_ADMIN} est un
 * rôle plateforme, distinct de {@code ROLE_ADMIN} qui est cantonné au tenant courant). Le seul
 * identifiant extrait du contexte de sécurité est celui du super admin appelant (pour le rate
 * limiting et l'audit) — jamais un {@code tenantId} accepté depuis le corps de la requête.
 */
@RestController
@RequestMapping("/superadmin/tenants")
public class SuperAdminTenantController {

    private static final Logger LOG = LoggerFactory.getLogger(SuperAdminTenantController.class);

    private final SuperAdminTenantService superAdminTenantService;
    private final CookieHelper cookieHelper;

    /**
     * Construit le contrôleur avec ses collaborateurs.
     *
     * @param superAdminTenantService service de supervision/création/vérification des tenants
     * @param cookieHelper            résolution de l'IP client, partagée avec les autres contrôleurs
     */
    public SuperAdminTenantController(
            final SuperAdminTenantService superAdminTenantService, final CookieHelper cookieHelper) {
        this.superAdminTenantService = superAdminTenantService;
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

        LOG.info("event=SUPERADMIN_TENANT_CREATED superAdminId={} tenantId={} slug={}",
                caller.getId(), response.id(), sanitizeForLog(response.slug()));
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
                "error", "TENANT_SLUG_ALREADY_EXISTS",
                "message", "Ce slug est déjà utilisé par un autre tenant"));
    }

    /**
     * Traduit un slug réservé en {@code 422 Unprocessable Entity}.
     *
     * @return corps d'erreur {@code 422}
     */
    @ExceptionHandler(ReservedTenantSlugException.class)
    public ResponseEntity<Map<String, Object>> handleReservedSlug() {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "error", "TENANT_SLUG_RESERVED",
                "message", "Ce slug est réservé et ne peut pas être utilisé"));
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
}
