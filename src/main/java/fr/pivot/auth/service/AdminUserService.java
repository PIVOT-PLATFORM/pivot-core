package fr.pivot.auth.service;

import fr.pivot.auth.dto.AdminUserDto;
import fr.pivot.auth.dto.AssignableRole;
import fr.pivot.auth.dto.UserStatus;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.exception.AdminUserNotFoundException;
import fr.pivot.auth.exception.InvalidUserFilterException;
import fr.pivot.auth.exception.SelfRoleChangeForbiddenException;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.repository.UserSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;

/**
 * Service d'administration exposant la liste paginée des utilisateurs du tenant courant
 * (US06.1.1 — {@code GET /api/admin/users}).
 *
 * <p>Service dédié admin (comme {@code AdminModuleActivationService}) : porte lui-même
 * {@code @PreAuthorize("hasRole('ADMIN')")}, évalué par le proxy Spring Method Security
 * ({@code @EnableMethodSecurity}, {@code SecurityConfig}) à chaque appel — y compris si un
 * futur appelant interne oublie la vérification côté contrôleur.
 *
 * <p><strong>Isolation tenant :</strong> {@code tenantId} est un paramètre obligatoire résolu
 * exclusivement par l'appelant depuis le token porteur ({@code AdminUserController}) — jamais
 * depuis un paramètre de requête. Voir {@link UserSpecifications#forTenant(Long)}.
 */
@Service
public class AdminUserService {

    /** Taille de page par défaut si {@code size} est absent ou invalide (&le; 0). */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** Taille de page maximale — toute valeur supérieure est silencieusement plafonnée. */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * Rôles connus de la plateforme (voir CLAUDE.md « Schéma de rôles ») — seules valeurs
     * acceptées par le filtre {@code role}. La colonne {@code role} reste une {@code VARCHAR(50)}
     * libre en base (pas d'énumération SQL), mais le référentiel applicatif des rôles est fermé.
     */
    static final Set<String> KNOWN_ROLES =
            Set.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN", "ROLE_USER", "ROLE_GUEST");

    private final UserRepository userRepository;
    private final TokenService tokenService;

    /**
     * Construit le service avec ses collaborateurs.
     *
     * @param userRepository accès aux utilisateurs, avec support {@link Specification}
     * @param tokenService   révocation des tokens actifs — US06.1.3 : un changement de rôle doit
     *                       invalider immédiatement toute session portant l'ancien rôle en cache
     */
    public AdminUserService(final UserRepository userRepository, final TokenService tokenService) {
        this.userRepository = userRepository;
        this.tokenService = tokenService;
    }

    /**
     * Liste les utilisateurs du tenant donné, paginés et filtrés.
     *
     * @param tenantId identifiant du tenant, résolu exclusivement depuis le token porteur
     * @param page     numéro de page demandé (0-indexed) — toute valeur négative est ramenée à 0
     * @param size     taille de page demandée — clampée entre 1 et {@link #MAX_PAGE_SIZE},
     *                 {@link #DEFAULT_PAGE_SIZE} si &le; 0
     * @param role     filtre optionnel sur le rôle exact (ex. {@code ROLE_ADMIN}), ou
     *                 {@code null}/vide pour ne pas filtrer
     * @param status   filtre optionnel sur le statut synthétique ({@link UserStatus}), ou
     *                 {@code null}/vide pour ne pas filtrer
     * @param search   filtre optionnel plein-texte (e-mail, prénom ou nom), ou {@code null}/vide
     *                 pour ne pas filtrer
     * @return page de {@link AdminUserDto} — jamais les entités {@link User} directement
     * @throws InvalidUserFilterException si {@code role} est fourni mais ne correspond à aucun
     *                                     rôle connu de la plateforme, ou si {@code status} est
     *                                     fourni mais ne correspond à aucune valeur de
     *                                     {@link UserStatus}
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public Page<AdminUserDto> listUsers(
            final Long tenantId,
            final int page,
            final int size,
            final String role,
            final String status,
            final String search) {
        final String roleFilter = validateRole(role);
        final UserStatus statusFilter = parseStatus(status);
        final Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                clampSize(size),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));

        final Specification<User> spec = Specification.<User>where(UserSpecifications.forTenant(tenantId))
                .and(UserSpecifications.notDeleted())
                .and(UserSpecifications.withRole(roleFilter))
                .and(UserSpecifications.withStatus(statusFilter))
                .and(UserSpecifications.matchingSearch(search));

        return userRepository.findAll(spec, pageable).map(AdminUserDto::from);
    }

    /**
     * Modifie le rôle d'un utilisateur du tenant courant (US06.1.3 — {@code PATCH
     * /api/admin/users/{userId}/role}) et révoque immédiatement tous ses tokens actifs.
     *
     * <p><strong>Isolation tenant :</strong> {@code targetUserId} est vérifié appartenir à
     * {@code tenantId} — résolu exclusivement depuis le token porteur de l'appelant — avant tout
     * traitement. Un {@code targetUserId} inexistant ou d'un autre tenant lève
     * {@link AdminUserNotFoundException}, traduite en {@code 404} (jamais {@code 403}).
     *
     * <p><strong>Auto-rétrogradation :</strong> {@code targetUserId == callerUserId} est rejeté
     * avant toute lecture en base — un admin ne peut jamais changer son propre rôle par ce
     * endpoint, qu'il s'agisse d'une rétrogradation ou d'une re-promotion.
     *
     * <p><strong>Révocation de session :</strong> le rôle Spring Security n'est jamais mis en
     * cache côté token — {@link fr.pivot.auth.service.TokenService#validate} le résout depuis la
     * BDD à chaque requête via l'entité {@link User} rechargée. La révocation ci-dessous est donc
     * une défense en profondeur : elle garantit qu'un token émis avant le changement ne peut plus
     * être {@linkplain fr.pivot.auth.service.TokenService#validate validé} du tout après ce point,
     * plutôt que de dépendre uniquement de la fraîcheur du rôle résolu à la volée.
     *
     * @param tenantId     identifiant du tenant de l'administrateur appelant
     * @param callerUserId identifiant de l'administrateur appelant — jamais égal à
     *                     {@code targetUserId}
     * @param targetUserId identifiant de l'utilisateur dont le rôle doit changer (path variable)
     * @param role         nouveau rôle, restreint par construction à {@link AssignableRole}
     * @return le {@link AdminUserDto} de l'utilisateur après modification
     * @throws SelfRoleChangeForbiddenException si {@code targetUserId} désigne l'appelant lui-même
     * @throws AdminUserNotFoundException       si {@code targetUserId} n'existe pas dans
     *                                           {@code tenantId}
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AdminUserDto updateRole(
            final Long tenantId,
            final Long callerUserId,
            final Long targetUserId,
            final AssignableRole role) {
        if (targetUserId.equals(callerUserId)) {
            throw new SelfRoleChangeForbiddenException(callerUserId);
        }

        final User target = userRepository.findByIdAndTenantIdAndDeletedAtIsNull(targetUserId, tenantId)
                .orElseThrow(() -> new AdminUserNotFoundException(targetUserId));

        target.setRole(role.name());
        userRepository.save(target);

        // Défense en profondeur : voir JavaDoc ci-dessus. Aucune session de la cible ne doit
        // survivre au changement de rôle, même si un futur appelant du token oublie de relire
        // le rôle depuis la BDD.
        tokenService.revokeAllForUser(target.getId());

        return AdminUserDto.from(target);
    }

    /**
     * Clampe la taille de page demandée entre 1 et {@link #MAX_PAGE_SIZE}.
     *
     * @param size taille demandée, potentiellement hors bornes
     * @return {@link #DEFAULT_PAGE_SIZE} si {@code size <= 0}, sinon {@code size} plafonné à
     *     {@link #MAX_PAGE_SIZE}
     */
    static int clampSize(final int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * Valide le filtre {@code role} contre le référentiel fermé des rôles connus de la
     * plateforme ({@link #KNOWN_ROLES}) — symétrique de {@link #parseStatus(String)} : une
     * valeur inconnue lève une erreur plutôt que de retourner silencieusement une page vide.
     *
     * @param role valeur brute du paramètre de requête, ou {@code null}/vide
     * @return {@code null} si aucun filtre fourni, sinon {@code role} inchangé (comparaison
     *     stricte, cf. {@link UserSpecifications#withRole(String)})
     * @throws InvalidUserFilterException si la valeur ne correspond à aucun rôle connu
     */
    static String validateRole(final String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        if (!KNOWN_ROLES.contains(role)) {
            throw new InvalidUserFilterException("role", role);
        }
        return role;
    }

    /**
     * Interprète le filtre {@code status} en {@link UserStatus}.
     *
     * @param status valeur brute du paramètre de requête, ou {@code null}/vide
     * @return {@code null} si aucun filtre fourni, sinon la valeur {@link UserStatus} résolue
     * @throws InvalidUserFilterException si la valeur ne correspond à aucun {@link UserStatus}
     */
    static UserStatus parseStatus(final String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return UserStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException ex) {
            throw new InvalidUserFilterException("status", status);
        }
    }
}
