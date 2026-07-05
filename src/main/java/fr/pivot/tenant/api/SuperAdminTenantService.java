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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service de gestion plateforme des tenants — US06.2.3 « Super admin liste tous les tenants »,
 * US06.2.1 « Super admin crée un tenant » et US06.2.2 « Super admin désactive un tenant ».
 *
 * <p>Portée volontairement cross-tenant : {@code ROLE_SUPER_ADMIN} est un rôle plateforme (voir
 * CLAUDE.md, tableau des rôles), distinct de {@code ROLE_ADMIN} cantonné au tenant courant —
 * {@code TenantContext} (isolation par tenant) ne s'applique pas ici par conception, ces
 * endpoints existent précisément pour parcourir/créer/désactiver des tenants à l'échelle de la
 * plateforme. Même motif RBAC que {@code AdminModuleActivationService} : {@code
 * @PreAuthorize("hasRole('SUPER_ADMIN')")} est porté par ce service, pas le contrôleur, pour
 * qu'un futur appelant interne ne puisse jamais le contourner.
 *
 * <p><strong>Rate limiting (création) :</strong> {@link RateLimiterService#checkAndRecord} est
 * évalué en premier, avant toute autre règle métier (le format du slug est déjà rejeté en amont
 * par la validation bean de {@link CreateTenantRequest}, mais les vérifications réservé/unicité
 * ci-dessous restent protégées) — même ordre que {@code RegistrationService#register}, pour ne
 * pas laisser les réponses « slug réservé » ou « slug déjà pris » servir d'oracle gratuit
 * au-delà du débit autorisé.
 *
 * <p><strong>Stratégie de révocation en masse (désactivation) :</strong> désactiver un tenant
 * doit invalider immédiatement les sessions de tous ses utilisateurs, quel que soit leur nombre,
 * en moins de 500ms. Plutôt que de parcourir et révoquer individuellement chaque
 * {@code AccessToken} (O(n) utilisateurs — trop lent et non borné), une seule colonne
 * {@code tenant_invalidation_timestamp} est mise à jour sur la ligne {@code Tenant} (O(1)).
 * {@code TokenService#validate} rejette ensuite tout token émis avant ou au moment de cet
 * horodatage — voir cette méthode pour l'application de la règle à chaque requête.
 */
@Service
public class SuperAdminTenantService {

    private static final Logger LOG = LoggerFactory.getLogger(SuperAdminTenantService.class);

    private static final int CREATION_MAX_PER_HOUR = 10;
    private static final Duration CREATION_WINDOW = Duration.ofHours(1);

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final RateLimiterService rateLimiter;
    private final AuditService auditService;
    private final String appUrl;
    private final String systemTenantSlug;

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
     * @param systemTenantSlug slug du tenant système hébergeant les comptes
     *                         {@code ROLE_SUPER_ADMIN} — configurable ({@code
     *                         pivot.tenant.system-tenant-slug}), jamais un identifiant en dur,
     *                         car ce tenant ne peut jamais être désactivé via cet endpoint
     */
    public SuperAdminTenantService(
            final TenantRepository tenantRepository,
            final UserRepository userRepository,
            final RateLimiterService rateLimiter,
            final AuditService auditService,
            @Value("${pivot.app.url:http://localhost:4200}") final String appUrl,
            @Value("${pivot.tenant.system-tenant-slug:pivot-saas}") final String systemTenantSlug) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.appUrl = appUrl;
        this.systemTenantSlug = systemTenantSlug;
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
     * Désactive un tenant : passe {@code active} à {@code false} et pose
     * {@code tenant_invalidation_timestamp} à l'instant présent, révoquant ainsi en O(1)
     * l'ensemble des sessions actives de tous les utilisateurs de ce tenant.
     *
     * <p><strong>RBAC :</strong> {@code @PreAuthorize("hasRole('SUPER_ADMIN')")} — un appel
     * porté par un rôle inférieur lève {@link org.springframework.security.access.AccessDeniedException},
     * traduite en {@code 403} par le comportement par défaut de Spring Security.
     *
     * <p><strong>Protection du tenant système :</strong> le tenant identifié par
     * {@link #systemTenantSlug} (hébergeant les comptes {@code ROLE_SUPER_ADMIN}) ne peut
     * jamais être désactivé via cette méthode — le désactiver révoquerait potentiellement la
     * session de l'appelant lui-même et rendrait la plateforme inadministrable.
     *
     * <p><strong>Confirmation avant retour :</strong> {@code saveAndFlush} force l'exécution
     * de l'{@code UPDATE} avant que cette méthode ne retourne ; combiné à {@code @Transactional}
     * (commit au retour du proxy Spring), l'appelant ne reçoit un résultat qu'une fois la
     * révocation bulk effectivement confirmée en base.
     *
     * @param tenantId  identifiant du tenant à désactiver (jamais accepté depuis le corps de
     *                  requête — voir {@link SuperAdminTenantController})
     * @param status    valeur brute du statut demandé — doit être {@link TenantStatusRequest#INACTIVE}
     * @return l'entité {@link Tenant} désactivée, à jour
     * @throws TenantNotFoundException            si aucun tenant ne correspond à {@code tenantId}
     * @throws SystemTenantProtectedException     si {@code tenantId} désigne le tenant système
     * @throws UnsupportedTenantStatusException   si {@code status} n'est pas {@code "INACTIVE"}
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public Tenant updateStatus(final Long tenantId, final String status) {
        if (!TenantStatusRequest.INACTIVE.equals(status)) {
            throw new UnsupportedTenantStatusException(status);
        }

        final Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new TenantNotFoundException(tenantId));

        if (isSystemTenant(tenant)) {
            LOG.warn("event=SYSTEM_TENANT_DEACTIVATION_BLOCKED tenantId={}", tenantId);
            throw new SystemTenantProtectedException(tenantId);
        }

        final Instant invalidationTimestamp = Instant.now();
        tenant.setActive(false);
        tenant.setTenantInvalidationTimestamp(invalidationTimestamp);
        final Tenant saved = tenantRepository.saveAndFlush(tenant);

        LOG.info("event=TENANT_DEACTIVATED tenantId={} invalidationTimestamp={}",
            tenantId, invalidationTimestamp);
        return saved;
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

    /**
     * Indique si {@code tenant} est le tenant système hébergeant les comptes
     * {@code ROLE_SUPER_ADMIN} (identifié par {@link #systemTenantSlug}, jamais un id en dur).
     *
     * @param tenant le tenant à qualifier
     * @return {@code true} si ce tenant ne doit jamais pouvoir être désactivé via cet endpoint
     */
    private boolean isSystemTenant(final Tenant tenant) {
        return systemTenantSlug.equalsIgnoreCase(tenant.getSlug());
    }
}
