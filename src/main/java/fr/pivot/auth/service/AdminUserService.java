package fr.pivot.auth.service;

import fr.pivot.auth.dto.AdminUserDto;
import fr.pivot.auth.dto.UserStatus;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.exception.InvalidUserFilterException;
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

    private final UserRepository userRepository;

    /**
     * Construit le service avec son collaborateur.
     *
     * @param userRepository accès aux utilisateurs, avec support {@link Specification}
     */
    public AdminUserService(final UserRepository userRepository) {
        this.userRepository = userRepository;
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
     * @throws InvalidUserFilterException si {@code status} est fourni mais ne correspond à
     *                                     aucune valeur de {@link UserStatus}
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
        final UserStatus statusFilter = parseStatus(status);
        final Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                clampSize(size),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));

        final Specification<User> spec = Specification.<User>where(UserSpecifications.forTenant(tenantId))
                .and(UserSpecifications.notDeleted())
                .and(UserSpecifications.withRole(role))
                .and(UserSpecifications.withStatus(statusFilter))
                .and(UserSpecifications.matchingSearch(search));

        return userRepository.findAll(spec, pageable).map(AdminUserDto::from);
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
