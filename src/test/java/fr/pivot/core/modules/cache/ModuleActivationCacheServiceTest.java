package fr.pivot.core.modules.cache;

import fr.pivot.core.modules.ModuleActivationService;
import fr.pivot.core.modules.event.ModuleActivatedEvent;
import fr.pivot.core.modules.event.ModuleDeactivatedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires pour {@link ModuleActivationCacheService} — cache-aside Redis du
 * statut d'activation des modules PIVOT.
 *
 * <p>Traçabilité EN03.3 :
 * <ul>
 *   <li>clé de cache {@code module:status:{tenantId}:{moduleId}} ;</li>
 *   <li>TTL configurable ({@code modules.cache.ttl-seconds}) ;</li>
 *   <li>invalidation immédiate sur événement d'activation/désactivation ;</li>
 *   <li>fallback BDD si Redis indisponible (jamais d'exception propagée) ;</li>
 *   <li>métriques Micrometer hit/miss.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModuleActivationCacheServiceTest {

    private static final Long TENANT_ID = 42L;
    private static final String MODULE_ID = "whiteboard";
    private static final String KEY = "module:status:42:whiteboard";
    private static final long TTL_SECONDS = 60L;

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private ModuleActivationService activationService;

    private MeterRegistry meterRegistry;
    private ModuleActivationCacheService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new ModuleActivationCacheService(redis, activationService, meterRegistry, TTL_SECONDS);
    }

    // ----------------------------------------------------------------
    // Cache hit
    // ----------------------------------------------------------------

    @Test
    void isEnabled_returnsCachedTrue_onHit_withoutCallingActivationService() {
        when(valueOps.get(KEY)).thenReturn("true");

        final boolean result = service.isEnabled(TENANT_ID, MODULE_ID);

        assertThat(result).isTrue();
        verifyNoInteractions(activationService);
        assertThat(hitCount()).isEqualTo(1.0);
        assertThat(missCount()).isEqualTo(0.0);
    }

    @Test
    void isEnabled_returnsCachedFalse_onHit() {
        when(valueOps.get(KEY)).thenReturn("false");

        assertThat(service.isEnabled(TENANT_ID, MODULE_ID)).isFalse();
        verifyNoInteractions(activationService);
    }

    // ----------------------------------------------------------------
    // Cache miss
    // ----------------------------------------------------------------

    @Test
    void isEnabled_fallsBackToActivationService_andPopulatesCache_onMiss() {
        when(valueOps.get(KEY)).thenReturn(null);
        when(activationService.isEnabled(TENANT_ID, MODULE_ID)).thenReturn(true);

        final boolean result = service.isEnabled(TENANT_ID, MODULE_ID);

        assertThat(result).isTrue();
        verify(activationService).isEnabled(TENANT_ID, MODULE_ID);
        verify(valueOps).set(KEY, "true", TTL_SECONDS, TimeUnit.SECONDS);
        assertThat(missCount()).isEqualTo(1.0);
        assertThat(hitCount()).isEqualTo(0.0);
    }

    @Test
    void isEnabled_populatesCacheWithFalse_whenModuleDisabled() {
        when(valueOps.get(KEY)).thenReturn(null);
        when(activationService.isEnabled(TENANT_ID, MODULE_ID)).thenReturn(false);

        assertThat(service.isEnabled(TENANT_ID, MODULE_ID)).isFalse();
        verify(valueOps).set(KEY, "false", TTL_SECONDS, TimeUnit.SECONDS);
    }

    // ----------------------------------------------------------------
    // Redis indisponible — fallback BDD, jamais d'exception propagée
    // ----------------------------------------------------------------

    @Test
    void isEnabled_fallsBackToActivationService_whenRedisReadFails() {
        when(valueOps.get(KEY)).thenThrow(new RedisConnectionFailureException("connection refused"));
        when(activationService.isEnabled(TENANT_ID, MODULE_ID)).thenReturn(true);

        final boolean result = service.isEnabled(TENANT_ID, MODULE_ID);

        assertThat(result).isTrue();
        verify(activationService).isEnabled(TENANT_ID, MODULE_ID);
        assertThat(missCount()).isEqualTo(1.0);
    }

    @Test
    void isEnabled_doesNotThrow_whenRedisWriteFailsAfterMiss() {
        when(valueOps.get(KEY)).thenReturn(null);
        when(activationService.isEnabled(TENANT_ID, MODULE_ID)).thenReturn(true);
        doThrow(new QueryTimeoutException("timeout"))
                .when(valueOps).set(eq(KEY), eq("true"), anyLong(), eq(TimeUnit.SECONDS));

        final boolean result = service.isEnabled(TENANT_ID, MODULE_ID);

        assertThat(result).isTrue();
    }

    // ----------------------------------------------------------------
    // Invalidation immédiate sur événement
    // ----------------------------------------------------------------

    @Test
    void onModuleActivated_writesThroughCache_withTrue() {
        service.onModuleActivated(new ModuleActivatedEvent(TENANT_ID, MODULE_ID, Instant.now()));

        verify(valueOps).set(KEY, "true", TTL_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void onModuleDeactivated_writesThroughCache_withFalse() {
        service.onModuleDeactivated(new ModuleDeactivatedEvent(TENANT_ID, MODULE_ID, Instant.now()));

        verify(valueOps).set(KEY, "false", TTL_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void onModuleActivated_doesNotThrow_whenRedisUnavailable() {
        doThrow(new RedisConnectionFailureException("down"))
                .when(valueOps).set(eq(KEY), eq("true"), anyLong(), eq(TimeUnit.SECONDS));

        service.onModuleActivated(new ModuleActivatedEvent(TENANT_ID, MODULE_ID, Instant.now()));

        verify(valueOps, times(1)).set(eq(KEY), eq("true"), anyLong(), eq(TimeUnit.SECONDS));
    }

    // ----------------------------------------------------------------
    // Helpers métriques
    // ----------------------------------------------------------------

    private double hitCount() {
        final Counter counter = meterRegistry.find("pivot.modules.cache.hit").counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double missCount() {
        final Counter counter = meterRegistry.find("pivot.modules.cache.miss").counter();
        return counter == null ? 0.0 : counter.count();
    }
}
