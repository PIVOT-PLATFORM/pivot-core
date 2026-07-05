package fr.pivot.tenant.api;

import fr.pivot.auth.entity.User;
import fr.pivot.auth.exception.RateLimitException;
import fr.pivot.auth.repository.TenantUserCountProjection;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.service.AuditService;
import fr.pivot.auth.service.RateLimiterService;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service de gestion plateforme des tenants — US06.2.3 « Super admin liste tous les tenants » et
 * US06.2.1 « Super admin crée un tenant ».
 *
 * <p>Portée volontairement cross-tenant : {@code ROLE_SUPER_ADMIN} est un rôle plateforme (voir
 * CLAUDE.md, tableau des rôles), distinct de {@code ROLE_ADMIN} cantonné au tenant courant —
 * {@code TenantContext} (isolation par tenant) ne s'applique pas ici par conception, ces
 * endpoints existent précisément pour parcourir/créer des tenants à l'échelle de la plateforme.
 * Même motif RBAC que {@code AdminModuleActivationService} : {@code
 * @PreAuthorize("hasRole('SUPER_ADMIN')")} est porté par ce service, pas le contrôleur, pour
 * qu'un futur appelant interne ne puisse jamais le contourner.
 *
 * <p>Portera bientôt aussi {@code updateStatus} (US06.2.2, PR #135, encore ouverte — même classe,
 * même package). PR #135 crée sa propre migration {@code V4__tenant_invalidation_timestamp.sql},
 * qui collisionne avec {@code V6__tenant_auth_mode_creation_values.sql} de cette PR (déjà
 * renumérotée depuis {@code V4} lors de cette fusion) — sa propre renumérotation restera
 * nécessaire à son intégration, en plus de la fusion service/contrôleur.
 *
 * <p><strong>Rate limiting (création) :</strong> {@link RateLimiterService#checkAndRecord} est
 * évalué en premier, avant toute autre règle métier (le format du slug est déjà rejeté en amont
 * par la validation bean de {@link CreateTenantRequest}, mais les vérifications réservé/unicité
 * ci-dessous restent protégées) — même ordre que {@code RegistrationService#register}, pour ne
 * pas laisser les réponses « slug réservé » ou « slug déjà pris » servir d'oracle gratuit
 * au-delà du débit autorisé.
 */
@Service
public class SuperAdminTenantService {

    private static final int CREATION_MAX_PER_HOUR = 10;
    private static final Duration CREATION_WINDOW = Duration.ofHours(1);

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final RateLimiterService rateLimiter;
    private final AuditService auditService;
    private final String appUrl;

    /**
     * Construit le service avec ses collaborateurs.
     *
     * @param tenantRepository dépôt des tenants
     * @param userRepository   dépôt des utilisateurs (décompte par tenant)
     * @param rateLimiter      limiteur de débit Redis (sliding window)
     * @param auditService     journal d'audit applicatif
     * @param appUrl           URL de base du frontend, utilisée pour construire l'URL
     *                         d'invitation (même propriété que {@link
     *                         fr.pivot.auth.service.EmailService})
     */
    public SuperAdminTenantService(
            final TenantRepository tenantRepository,
            final UserRepository userRepository,
            final RateLimiterService rateLimiter,
            final AuditService auditService,
            @Value("${pivot.app.url:http://localhost:4200}") final String appUrl) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.appUrl = appUrl;
    }

    /**
     * Liste paginée et filtrée de tous les tenants de la plateforme, avec le nombre
     * d'utilisateurs non supprimés rattachés à chacun.
     *
     * <p><strong>RBAC :</strong> {@code @PreAuthorize("hasRole('SUPER_ADMIN')")} — un appel
     * porté par un rôle inférieur (ex. {@code ROLE_ADMIN}) lève
     * {@link org.springframework.security.access.AccessDeniedException}, traduite en {@code 403}
     * par le comportement par défaut de Spring Security (pas de gestionnaire custom nécessaire).
     *
     * <p>{@code userCount} est calculé par une requête d'agrégation groupée sur la seule page
     * courante ({@link UserRepository#countActiveUsersByTenantIds}) — jamais un compteur
     * dénormalisé sur {@link Tenant}, et jamais de requête N+1 (un seul aller-retour BDD pour
     * l'ensemble des tenants de la page).
     *
     * @param name     filtre optionnel — sous-chaîne du nom, insensible à la casse
     * @param active   filtre optionnel — statut actif/inactif
     * @param plan     filtre optionnel — plan exact
     * @param authMode filtre optionnel — mode d'authentification exact
     * @param pageable pagination et tri (défaut porté par le contrôleur : {@code createdAt DESC}, taille 20)
     * @return page de {@link TenantSummaryDto}
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Page<TenantSummaryDto> listTenants(
            final String name,
            final Boolean active,
            final String plan,
            final String authMode,
            final Pageable pageable) {
        final Page<Tenant> tenants = tenantRepository.findAll(
                TenantSpecifications.filter(name, active, plan, authMode), pageable);

        final List<Long> tenantIds = tenants.getContent().stream().map(Tenant::getId).toList();
        final Map<Long, Long> userCounts = tenantIds.isEmpty()
                ? Map.of()
                : userRepository.countActiveUsersByTenantIds(tenantIds).stream()
                        .collect(Collectors.toMap(
                                TenantUserCountProjection::getTenantId,
                                TenantUserCountProjection::getUserCount));

        return tenants.map(tenant -> TenantSummaryDto.from(tenant, userCounts.getOrDefault(tenant.getId(), 0L)));
    }

    /**
     * Crée un nouveau tenant sur la plateforme.
     *
     * <p>Validations dans l'ordre : (1) rate limit — 10 créations/heure par compte super
     * admin, (2) slug réservé ({@code 422}), (3) slug déjà pris ({@code 409}). Le format du
     * slug (regex, longueur) est déjà garanti par la validation bean de {@link
     * CreateTenantRequest} avant que ce point d'entrée ne soit atteint.
     *
     * @param request   payload validé (nom, slug, plan, mode d'authentification)
     * @param caller    le super admin authentifié effectuant l'appel — jamais déduit du body
     * @param ip        IP cliente, pour le rate limiting et l'audit
     * @param userAgent user-agent client, pour l'audit
     * @return l'ID du tenant créé, son slug et l'URL d'invitation pour son premier admin
     * @throws RateLimitException              429 — plus de 10 créations dans l'heure pour ce compte
     * @throws ReservedTenantSlugException     422 — slug sur la liste des termes réservés
     * @throws TenantSlugAlreadyExistsException 409 — slug déjà utilisé par un autre tenant
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public CreateTenantResponse createTenant(
            final CreateTenantRequest request, final User caller, final String ip, final String userAgent) {
        final String bucket = rateLimiter.tenantCreationBucket(String.valueOf(caller.getId()));
        if (!rateLimiter.checkAndRecord(bucket, CREATION_MAX_PER_HOUR, CREATION_WINDOW)) {
            // Direct 6-arg call (not the deferred-until-commit convenience): this method throws
            // right after, rolling back the enclosing @Transactional — an afterCommit-deferred
            // write would never fire. No FK-visibility concern here either (tenant is null,
            // caller was already committed long before this request) — REQUIRES_NEW lets this
            // write commit independently and survive the outer rollback.
            auditService.log(caller, null, AuditService.TENANT_CREATION_RATE_LIMIT_EXCEEDED, ip, userAgent, null);
            throw new RateLimitException(Math.max(1L, rateLimiter.getRemainingSeconds(bucket)));
        }

        final String slug = request.slug();
        if (TenantSlugPolicy.isReserved(slug)) {
            throw new ReservedTenantSlugException(slug);
        }
        if (tenantRepository.findBySlug(slug).isPresent()) {
            throw new TenantSlugAlreadyExistsException(slug);
        }

        final Tenant tenant = new Tenant();
        tenant.setName(request.name());
        tenant.setSlug(slug);
        tenant.setPlan(request.plan());
        tenant.setAuthMode(request.authMode());
        tenant.setActive(true);
        final Tenant saved = tenantRepository.save(tenant);

        auditService.log(caller, saved, AuditService.TENANT_CREATED, ip, userAgent);

        return new CreateTenantResponse(saved.getId(), saved.getSlug(), buildInvitationUrl(saved.getSlug()));
    }

    /**
     * Vérifie la disponibilité d'un slug — {@code GET /api/superadmin/tenants/check-slug},
     * utilisé par le formulaire Angular pour la vérification temps réel (debounce 500ms).
     *
     * <p>Aucun rate limit dédié : c'est une lecture seule sans effet de bord, déjà protégée en
     * amont par {@code ROLE_SUPER_ADMIN} (accès plateforme restreint, pas un endpoint public).
     *
     * @param slug slug candidat, tel que saisi par l'utilisateur (peut être {@code null}/vide/mal formé)
     * @return disponibilité et, si indisponible, la raison ({@code INVALID_FORMAT}, {@code
     *     RESERVED} ou {@code TAKEN})
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public SlugAvailabilityResponse checkSlugAvailability(final String slug) {
        if (!TenantSlugPolicy.matchesFormat(slug)) {
            return SlugAvailabilityResponse.invalidFormat();
        }
        if (TenantSlugPolicy.isReserved(slug)) {
            return SlugAvailabilityResponse.reserved();
        }
        if (tenantRepository.findBySlug(slug).isPresent()) {
            return SlugAvailabilityResponse.taken();
        }
        return SlugAvailabilityResponse.ofAvailable();
    }

    /**
     * Construit l'URL d'invitation du premier admin d'un tenant nouvellement créé.
     *
     * <p><strong>Simplification documentée :</strong> PIVOT n'a pas encore de système
     * d'invitation à token sécurisé par personne (ce serait une US distincte, non planifiée à
     * ce jour — « inviter un admin nommé sur un tenant »). Cette URL route simplement vers
     * l'écran d'inscription, scopé au tenant via un paramètre de requête, en réutilisant la
     * même convention que {@link fr.pivot.auth.service.EmailService} ({@code appUrl +
     * "/auth/..."}). Le premier compte qui s'inscrit sur ce tenant obtient {@code ROLE_USER}
     * par défaut — l'élévation en {@code ROLE_ADMIN} du premier arrivant reste hors périmètre
     * de cette US (US06.2.1 ne couvre que la création du tenant lui-même).
     *
     * @param slug slug du tenant nouvellement créé
     * @return URL absolue vers l'écran d'inscription scopé au tenant
     */
    private String buildInvitationUrl(final String slug) {
        return appUrl + "/auth/register?tenant=" + slug;
    }
}
