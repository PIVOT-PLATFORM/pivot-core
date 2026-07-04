package fr.pivot.auth.controller;

import fr.pivot.auth.dto.AdminUserDto;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.exception.InvalidUserFilterException;
import fr.pivot.auth.service.AdminUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller d'administration des utilisateurs PIVOT — liste paginée des utilisateurs du
 * tenant courant (US06.1.1 « Admin liste utilisateurs de son tenant »).
 *
 * <p>Responsabilité unique : résolution du contexte tenant/utilisateur depuis le
 * {@link SecurityContextHolder} (même schéma que {@code fr.pivot.modules.api.AdminModuleController}),
 * délégation à {@link AdminUserService}, et traduction des exceptions métier en réponses HTTP.
 * Aucune logique métier (pagination, filtrage) dans ce contrôleur.
 *
 * <p><strong>Isolation tenant :</strong> le {@code tenantId} n'est jamais accepté depuis un
 * paramètre de requête, le corps ou un en-tête — uniquement depuis l'entité {@link User} posée
 * par {@code fr.pivot.config.TokenAuthenticationFilter} dans les détails de l'authentification
 * courante. Un client qui tente de passer un {@code tenantId} en query param n'a strictement
 * aucun effet : le paramètre n'existe même pas dans la signature de {@link #list}.
 *
 * <p><strong>RBAC :</strong> porté par {@link AdminUserService#listUsers} ({@code @PreAuthorize}
 * sur le service, pas sur ce contrôleur) — un appel {@code ROLE_USER} lève
 * {@link org.springframework.security.access.AccessDeniedException}, traduite en {@code 403}
 * par le comportement par défaut de Spring Security.
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private static final Logger LOG = LoggerFactory.getLogger(AdminUserController.class);

    private final AdminUserService adminUserService;

    /**
     * Construit le contrôleur avec son collaborateur.
     *
     * @param adminUserService service de listing réservé aux administrateurs
     */
    public AdminUserController(final AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    /**
     * Liste les utilisateurs du tenant de l'administrateur authentifié, paginés et filtrés.
     *
     * @param page   numéro de page (0-indexed), défaut {@code 0}
     * @param size   taille de page demandée, défaut {@code 20} — plafonnée à 100 par
     *               {@link AdminUserService}
     * @param role   filtre optionnel sur le rôle exact (ex. {@code ROLE_ADMIN})
     * @param status filtre optionnel sur le statut ({@code ACTIVE}, {@code INACTIVE},
     *               {@code BLOCKED})
     * @param search filtre optionnel plein-texte (e-mail, prénom ou nom)
     * @return {@code 200} avec une page Spring Data de {@link AdminUserDto} · {@code 401} si le
     *     contexte d'authentification est invalide · {@code 400} si {@code status} est invalide
     */
    @GetMapping
    public ResponseEntity<Page<AdminUserDto>> list(
            @RequestParam(name = "page", defaultValue = "0") final int page,
            @RequestParam(name = "size", defaultValue = "20") final int size,
            @RequestParam(name = "role", required = false) final String role,
            @RequestParam(name = "status", required = false) final String status,
            @RequestParam(name = "search", required = false) final String search) {
        final ResolvedAdmin resolved = resolveAdmin();
        if (resolved == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        final Page<AdminUserDto> result =
                adminUserService.listUsers(resolved.tenantId(), page, size, role, status, search);
        return ResponseEntity.ok(result);
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
     *
     * @return le tenantId résolu, ou {@code null} si le contexte d'authentification est
     *     invalide ou si l'utilisateur n'appartient à aucun tenant
     */
    private ResolvedAdmin resolveAdmin() {
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

        return new ResolvedAdmin(user.getTenant().getId());
    }

    /**
     * Tenant résolu depuis l'administrateur authentifié, interne à ce contrôleur.
     *
     * @param tenantId identifiant du tenant de l'administrateur authentifié
     */
    private record ResolvedAdmin(Long tenantId) {
    }
}
