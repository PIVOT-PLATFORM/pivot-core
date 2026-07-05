package fr.pivot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;

/**
 * Global cap on {@code Pageable} page size for every controller parameter bound via
 * Spring Data's {@code @PageableDefault}/{@code Pageable} resolver (e.g.
 * {@code SuperAdminTenantController#list}).
 *
 * <p>Without an explicit cap, a caller can request an arbitrarily large {@code size} query
 * parameter, forcing a single database round-trip to materialize an unbounded result set in
 * memory. Every current caller of a {@code Pageable}-bound endpoint is restricted to a
 * trusted platform role ({@code ROLE_SUPER_ADMIN}), so this is a defense-in-depth guard
 * rather than a response to an exploitable gap — Spring Data clamps ({@code size} silently
 * capped, never rejected with an error) any requested size above {@link #MAX_PAGE_SIZE} rather
 * than throwing, so existing well-behaved callers are unaffected.
 */
@Configuration
public class PaginationConfig {

    /**
     * Maximum number of elements returned on a single page, regardless of the {@code size}
     * query parameter requested by the caller.
     */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * Caps every {@code Pageable}-bound controller parameter at {@link #MAX_PAGE_SIZE}.
     *
     * @return the customizer applied by Spring Data's autoconfigured
     *     {@code PageableHandlerMethodArgumentResolver}
     */
    @Bean
    public PageableHandlerMethodArgumentResolverCustomizer pageableCustomizer() {
        return resolver -> resolver.setMaxPageSize(MAX_PAGE_SIZE);
    }
}
