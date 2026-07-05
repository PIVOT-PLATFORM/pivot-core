package fr.pivot.auth.controller;

import fr.pivot.auth.dto.AdminUserDto;
import fr.pivot.auth.dto.UpdateUserRoleRequest;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.exception.AdminUserNotFoundException;
import fr.pivot.auth.exception.InvalidUserFilterException;
import fr.pivot.auth.exception.SelfRoleChangeForbiddenException;
import fr.pivot.auth.service.AdminUserService;
import fr.pivot.auth.service.AuditService;
import fr.pivot.config.CookieHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller d'administration des utilisateurs PIVOT — liste paginée des utilisateurs du
 * tenant courant (US06.1.1 « Admin liste utilisateurs de son tenant ») et modification du rôle
 * d'un utilisateur (US06.1.3 « Admin modifie le rôle d'un utilisateur »).
 *
 * <p>Responsabilité unique : résolution du contexte tenant/utilisateur depuis le
 * {@link SecurityContextHolder} (même schéma que {@code fr.pivot.modules.api.AdminModuleController}),
 * délégation à {@link AdminUserService}, et traduction des exceptions métier en réponses HTTP.
 * Aucune logique métier (pagination, filtrage, révocation de tokens) dans ce contrôleur.
 *
 * <p><strong>Isolation tenant :</strong> le {@code tenantId} n'est jamais accepté depuis un
 * paramètre de requête, le corps ou un en-tête — uniquement depuis l'entité {@link User} posée
 * par {@code fr.pivot.config.TokenAuthenticationFilter} dans les détails de l'authentification
 * courante. Un client qui tente de passer un {@code tenantId} en query param ou en body n'a
 * strictement aucun effet : le paramètre n'existe dans aucune signature de méthode de ce
 * contrôleur. Un {@code userId} de path variable (ex. {@link #updateRole}) est systématiquement
 * revérifié appartenir à ce tenant par {@link AdminUserService} avant tout traitement.
 *
 * <p><strong>RBAC :</strong> porté par {@link AdminUserService} ({@code @PreAuthorize} sur le
 * service, pas sur ce contrôleur) — un appel {@code ROLE_USER} lève
 * {@link org.springframework.security.access.AccessDeniedException}, traduite en {@code 403}
 * par le comportement par défaut de Spring Security.
 *
 * <p><strong>Note d'extension :</strong> une US soeur (désactivation/réactivation de compte)
 * ajoute {@code PATCH /api/admin/users/{userId}/status} à ce même contrôleur — le helper
 * {@link #resolveActor()} et le bloc « Exception handling » sont volontairement génériques
 * (pas de nom spécifique au rôle) pour être réutilisés tels quels par ce futur endpoint.
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private static final Logger LOG = LoggerFactory.getLogger(AdminUserController.class);

    private final AdminUserService adminUserService;
    private final AuditService auditService;
    private final CookieHelper cookieHelper;

    /**
     * Construit le contrôleur avec ses collaborateurs.
     *
     * @param adminUserService service métier réservé aux administrateurs (listing, modification)
     * @param auditService     journal d'audit applicatif
     * @param cookieHelper     résolution de l'IP client, partagée avec les autres contrôleurs
     */
    public AdminUserController(
            final AdminUserService adminUserService,
            final AuditService auditService,
            final CookieHelper cookieHelper) {
        this.adminUserService = adminUserService;
        this.auditService = auditService;
        this.cookieHelper = cookieHelper;
    }

    /**
     * Liste les utilisateurs du tenant de l'administrateur authentifié, paginés et filtrés.
     *
     * @param page   numéro de page (0-indexed), défaut {@code 0}
     * @param size   taille de page demandée, défaut {@code 20} — plafonnée à 100 par
     *               {@link AdminUserService}
     * @param role   filtre optionnel sur le rôle exact parmi les rôles connus de la plateforme
     *               (ex. {@code ROLE_ADMIN})
     * @param status filtre optionnel sur le statut ({@code ACTIVE}, {@code INACTIVE},
     *               {@code BLOCKED})
     * @param search filtre optionnel plein-texte (e-mail, prénom ou nom)
     * @return {@code 200} avec une page Spring Data de {@link AdminUserDto} · {@code 401} si le
     *     contexte d'authentification est invalide · {@code 400} si {@code role} ou
     *     {@code status} est invalide
     */
    @GetMapping
    public ResponseEntity<Page<AdminUserDto>> list(
            @RequestParam(name = "page", defaultValue = "0") final int page,
            @RequestParam(name = "size", defaultValue = "20") final int size,
            @RequestParam(name = "role", required = false) final String role,
            @RequestParam(name = "status", required = false) final String status,
            @RequestParam(name = "search", required = false) final String search) {
        final User actor = resolveActor();
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        final Page<AdminUserDto> result = adminUserService.listUsers(
                actor.getTenant().getId(), page, size, role, status, search);
        return ResponseEntity.ok(result);
    }

    /**
     * Modifie le rôle d'un utilisateur du tenant courant.
     *
     * @param userId      identifiant de l'utilisateur ciblé (path variable — jamais le corps)
     * @param request     corps de requête — {@code { "role": "ROLE_ADMIN" | "ROLE_USER" } }
     * @param httpRequest requête HTTP (résolution IP/User-Agent pour l'audit)
     * @return {@code 200} avec le {@link AdminUserDto} mis à jour · {@code 400} si {@code role}
     *     est absent ou hors de {@link fr.pivot.auth.dto.AssignableRole} (ex.
     *     {@code ROLE_SUPER_ADMIN}) · {@code 401} si le contexte d'authentification est invalide ·
     *     {@code 403} si l'appelant n'a pas {@code ROLE_ADMIN}, ou si {@code userId} désigne
     *     l'appelant lui-même (auto-rétrogradation interdite) · {@code 404} si {@code userId}
     *     n'existe pas dans le tenant courant. Après un {@code 200}, tous les tokens actifs de
     *     l'utilisateur ciblé sont immédiatement révoqués (voir
     *     {@link AdminUserService#updateRole}).
     */
    @PatchMapping("/{userId}/role")
    public ResponseEntity<AdminUserDto> updateRole(
            @PathVariable("userId") final Long userId,
            @Valid @RequestBody final UpdateUserRoleRequest request,
            final HttpServletRequest httpRequest) {
        final User actor = resolveActor();
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        final AdminUserDto updated = adminUserService.updateRole(
                actor.getTenant().getId(), actor.getId(), userId, request.role());

        auditService.log(actor, actor.getTenant(), AuditService.USER_ROLE_CHANGED,
                cookieHelper.clientIp(httpRequest), httpRequest.getHeader("User-Agent"),
                "{\"targetUserId\":" + userId + ",\"newRole\":\"" + request.role() + "\"}");

        LOG.info("event=ADMIN_USER_ROLE_CHANGED actorId={} targetUserId={} newRole={}",
                actor.getId(), userId, request.role());
        return ResponseEntity.ok(updated);
    }

    // ----------------------------------------------------------------
    // Exception handling — local à ce contrôleur (pas de handler global)
    // ----------------------------------------------------------------

    /**
     * Traduit un filtre invalide (ex. {@code status} inconnu) en {@code 400 Bad Request}.
     *
     * @param ex l'exception levée par le service
     * @return corps d'erreur {@code 400}
     */
    @ExceptionHandler(InvalidUserFilterException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidFilter(final InvalidUserFilterException ex) {
        LOG.warn("event=ADMIN_USERS_INVALID_FILTER field={} value={}",
                sanitizeForLog(ex.getField()), sanitizeForLog(ex.getValue()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "INVALID_FILTER",
                "field", ex.getField(),
                "message", "Valeur invalide pour le filtre '" + ex.getField() + "'"));
    }

    /**
     * Traduit un {@code userId} introuvable dans le tenant courant en {@code 404 Not Found}
     * (jamais {@code 403} — voir CLAUDE.md « Isolation tenant »).
     *
     * @param ex l'exception levée par le service
     * @return corps d'erreur {@code 404}
     */
    @ExceptionHandler(AdminUserNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleUserNotFound(final AdminUserNotFoundException ex) {
        LOG.warn("event=ADMIN_USER_NOT_FOUND");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "USER_NOT_FOUND",
                "message", "Cet utilisateur n'existe pas"));
    }

    /**
     * Traduit une tentative d'un admin de modifier son propre rôle en {@code 403 Forbidden}.
     *
     * @param ex l'exception levée par le service
     * @return corps d'erreur {@code 403}
     */
    @ExceptionHandler(SelfRoleChangeForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleSelfRoleChange(final SelfRoleChangeForbiddenException ex) {
        LOG.warn("event=ADMIN_SELF_ROLE_CHANGE_REJECTED");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", "SELF_ROLE_CHANGE_FORBIDDEN",
                "message", "Vous ne pouvez pas modifier votre propre rôle"));
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    /**
     * Neutralise les caractères de contrôle CR/LF d'une valeur avant de la loguer.
     *
     * <p>{@code field}/{@code value} proviennent de {@code @RequestParam} — données
     * utilisateur non fiables. Sans neutralisation, une valeur contenant {@code \r} ou
     * {@code \n} permettrait d'injecter de fausses lignes de log (CWE-117 / log forging).
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
     * <p>Le {@code tenantId} n'est jamais lu ailleurs que dans l'entité {@link User} posée par
     * le filtre d'authentification — jamais depuis le corps, un paramètre ou un en-tête.
     * Partagé par tous les endpoints de ce contrôleur (voir note d'extension dans le JavaDoc de
     * classe) — le futur endpoint {@code PATCH .../status} le réutilise tel quel.
     *
     * @return l'utilisateur authentifié, ou {@code null} si le contexte d'authentification est
     *     invalide ou si l'utilisateur n'appartient à aucun tenant
     */
    private User resolveActor() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !(auth.getDetails() instanceof User user)) {
            LOG.warn("event=ADMIN_USERS_REJECTED reason=invalid_auth_details type={}",
                    auth == null || auth.getDetails() == null ? "null" : auth.getDetails().getClass().getName());
            return null;
        }

        if (user.getTenant() == null) {
            LOG.warn("event=ADMIN_USERS_REJECTED reason=no_tenant userId={}", user.getId());
            return null;
        }

        return user;
    }
}
