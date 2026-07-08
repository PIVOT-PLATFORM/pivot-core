package fr.pivot.core.modules.cache;

import fr.pivot.core.modules.ModuleActivationService;
import fr.pivot.core.modules.event.ModuleActivatedEvent;
import fr.pivot.core.modules.event.ModuleDeactivatedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Cache-aside Redis pour le statut d'activation des modules PIVOT par tenant.
 *
 * <p>Enveloppe {@link ModuleActivationService#isEnabled(Long, String)} avec un cache Redis
 * clé {@code module:status:{tenantId}:{moduleId}} et un TTL configurable
 * ({@code modules.cache.ttl-seconds}, défaut 60s). Objectif : éviter une requête BDD à
 * chaque évaluation de module (guard, résolution de routes) sans jamais dépasser
 * {@code ttl-seconds} de latence entre un changement d'état et sa prise en compte.
 *
 * <p><strong>Invalidation immédiate</strong> : {@link ModuleActivatedEvent} et
 * {@link ModuleDeactivatedEvent} — publiés par {@link ModuleActivationService} sur chaque
 * transition effective — déclenchent une réécriture immédiate ({@code write-through}) de la
 * clé concernée, sans attendre l'expiration du TTL.
 *
 * <p><strong>Résilience Redis</strong> : toute {@link DataAccessException} (Redis indisponible,
 * timeout, erreur de connexion) est interceptée et journalisée en {@code WARN} — le service
 * bascule alors sur un appel direct à {@link ModuleActivationService}, jamais d'exception
 * propagée vers l'appelant (pas de 500 côté API).
 *
 * <p><strong>Observabilité</strong> : compteurs Micrometer {@code pivot.modules.cache.hit} et
 * {@code pivot.modules.cache.miss} pour le ratio hit/miss du cache.
 */
@Service
public class ModuleActivationCacheService {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleActivationCacheService.class);

    private static final String KEY_PREFIX = "module:status:";
    private static final String METRIC_HIT = "pivot.modules.cache.hit";
    private static final String METRIC_MISS = "pivot.modules.cache.miss";

    private final StringRedisTemplate redis;
    private final ModuleActivationService activationService;
    private final MeterRegistry meterRegistry;
    private final long ttlSeconds;

    /**
     * Construit le service avec ses collaborateurs.
     *
     * @param redis             template Redis (chaînes) partagé de l'application
     * @param activationService service source de vérité (BDD) pour l'état d'activation
     * @param meterRegistry     registre Micrometer pour les métriques de hit/miss
     * @param ttlSeconds        durée de vie des entrées de cache en secondes
     *                          ({@code modules.cache.ttl-seconds}, défaut 60)
     */
    public ModuleActivationCacheService(final StringRedisTemplate redis,
                                         final ModuleActivationService activationService,
                                         final MeterRegistry meterRegistry,
                                         @Value("${modules.cache.ttl-seconds:60}") final long ttlSeconds) {
        this.redis = redis;
        this.activationService = activationService;
        this.meterRegistry = meterRegistry;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * Indique si un module est activé pour un tenant, via cache-aside Redis.
     *
     * <p>Ordre de résolution :
     * <ol>
     *   <li>lecture Redis (clé {@code module:status:{tenantId}:{moduleId}}) ;</li>
     *   <li>si absente (miss) ou Redis indisponible : appel {@link ModuleActivationService} ;</li>
     *   <li>si Redis disponible : peuplement du cache avec le résultat, TTL {@link #ttlSeconds}.</li>
     * </ol>
     *
     * @param tenantId identifiant du tenant (issu du token porteur)
     * @param moduleId identifiant technique du module
     * @return {@code true} si le module est activé pour ce tenant, {@code false} sinon
     */
    public boolean isEnabled(final Long tenantId, final String moduleId) {
        final String key = cacheKey(tenantId, moduleId);

        try {
            final String cached = redis.opsForValue().get(key);
            if (cached != null) {
                meterRegistry.counter(METRIC_HIT).increment();
                return Boolean.parseBoolean(cached);
            }
        } catch (final DataAccessException ex) {
            LOG.warn("event=MODULE_CACHE_REDIS_UNAVAILABLE action=read tenantId={} moduleId={} error={}",
                    tenantId, moduleId, ex.getMessage());
            meterRegistry.counter(METRIC_MISS).increment();
            return activationService.isEnabled(tenantId, moduleId);
        }

        meterRegistry.counter(METRIC_MISS).increment();
        final boolean enabled = activationService.isEnabled(tenantId, moduleId);
        writeThrough(key, enabled);
        return enabled;
    }

    /**
     * Réécrit immédiatement le cache à l'activation d'un module — évite d'attendre le TTL.
     *
     * @param event événement publié par {@link ModuleActivationService#activate(Long, String)}
     */
    @EventListener
    public void onModuleActivated(final ModuleActivatedEvent event) {
        refreshCache(event.tenantId(), event.moduleId(), true);
    }

    /**
     * Réécrit immédiatement le cache à la désactivation d'un module — évite d'attendre le TTL.
     *
     * @param event événement publié par {@link ModuleActivationService#deactivate(Long, String)}
     */
    @EventListener
    public void onModuleDeactivated(final ModuleDeactivatedEvent event) {
        refreshCache(event.tenantId(), event.moduleId(), false);
    }

    private void refreshCache(final Long tenantId, final String moduleId, final boolean enabled) {
        writeThrough(cacheKey(tenantId, moduleId), enabled);
        LOG.info("event=MODULE_CACHE_INVALIDATED tenantId={} moduleId={} enabled={}",
                tenantId, moduleId, enabled);
    }

    private void writeThrough(final String key, final boolean enabled) {
        try {
            redis.opsForValue().set(key, String.valueOf(enabled), ttlSeconds, TimeUnit.SECONDS);
        } catch (final DataAccessException ex) {
            LOG.warn("event=MODULE_CACHE_REDIS_UNAVAILABLE action=write key={} error={}",
                    key, ex.getMessage());
        }
    }

    private static String cacheKey(final Long tenantId, final String moduleId) {
        return KEY_PREFIX + tenantId + ":" + moduleId;
    }
}
