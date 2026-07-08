package fr.pivot.core.autoconfigure;

import fr.pivot.core.db.ModuleFlywayConfigurer;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Root auto-configuration for {@code fr.pivot:pivot-core-starter}.
 *
 * <p>Activated via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * Consuming module repos ({@code pivot-xxx-core}) add {@code fr.pivot:pivot-core-starter} as a
 * dependency; this class configures the shared Spring context automatically.
 *
 * <h2>Exported packages</h2>
 *
 * <p>État vérifié fichier par fichier (2026-07-08, {@code pivot-core#171}) — cette liste ne
 * documente que ce qui existe réellement dans ce module, pas l'architecture cible :
 * <ul>
 *   <li>{@code fr.pivot.core.tenant} — {@code TenantContext} uniquement. {@code
 *       TenantContextHolder}/{@code @TenantAware} sont différés : aucun consommateur réel
 *       identifié à ce jour (tout le code existant passe {@code TenantContext} explicitement en
 *       paramètre, ex. {@link fr.pivot.core.modules.PivotModule#isEnabled(fr.pivot.core.tenant.TenantContext)})
 *       — les introduire
 *       maintenant serait de l'infrastructure spéculative. À réévaluer dès qu'un repo module a un
 *       besoin réel d'une résolution implicite (AOP/ThreadLocal) plutôt qu'un paramètre explicite.</li>
 *   <li>{@code fr.pivot.core.team} — {@code Team}, {@code TeamMember} (entités schéma public) —
 *       fait (EN17.1 volet team). Uniquement entités + repositories, aucune API REST ni logique
 *       métier tant qu'aucune US ne les spécifie.</li>
 *   <li>{@code fr.pivot.core.modules} — {@code PivotModule} interface, registre,
 *       {@code @RequiresModule} — fait.</li>
 *   <li>{@code fr.pivot.core.db} — Flyway baseline public schema, multi-schema DataSource config,
 *       {@link ModuleFlywayConfigurer} — fait.</li>
 * </ul>
 *
 * <p><strong>{@code fr.pivot.core.auth} — non extrait, décision d'architecture requise avant
 * implémentation</strong> (escaladé sur {@code pivot-core#171}, voir le commentaire de gate
 * associé). Le code applicatif ({@code fr.pivot.auth.*}, {@code pivot-core-app}) mélange logique
 * potentiellement générique (validation d'opaque token) et logique strictement propre à cette
 * application (endpoints login/register/2FA) — mais surtout, la validation de token
 * ({@code TokenService}/{@code TokenAuthenticationFilter}) dépend directement de l'entité JPA
 * concrète {@code fr.pivot.auth.entity.User}, ce qui empêche un déplacement mécanique : un
 * découplage propre nécessiterait d'introduire une abstraction de principal minimal partagée
 * (identité + rôle + tenant, sans les champs de profil propres à cette app), ce qui est un choix
 * de conception sur un composant de sécurité critique — hors périmètre d'une décision unilatérale
 * d'agent. Par ailleurs, aucun repo {@code pivot-xxx-core} n'a aujourd'hui de logique métier
 * implémentée (tous en bootstrap infrastructure uniquement), donc aucun besoin consommateur réel
 * et immédiat ne force cette extraction maintenant. La mention historique « OIDC resource server »
 * ne correspond plus au code actuel : {@code SecurityConfig} a explicitement remplacé
 * {@code oauth2ResourceServer().jwt()} par les tokens opaques (aucun décodeur JWT/JWKS générique
 * n'existe à extraire).
 */
@AutoConfiguration
public class PivotCoreAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PivotCoreAutoConfiguration.class);

    /**
     * Inner configuration that logs module-level Flyway configurers at startup.
     *
     * <p>Each {@code pivot-xxx-core} module repo declares a {@code @Bean Flyway} method that
     * delegates to {@link ModuleFlywayConfigurer#createFlyway(javax.sql.DataSource)} (see
     * {@link ModuleFlywayConfigurer} Javadoc for usage). This configuration logs each registered
     * {@link ModuleFlywayConfigurer} bean at startup so operators can verify that all module
     * schemas are discovered.
     *
     * <p>Conditional on {@link Flyway} being on the classpath — i.e. {@code flyway-core} is
     * present. This keeps the starter functional even when a consuming project opts out of Flyway
     * (test slice, embedded DB, etc.).
     */
    @Configuration
    @ConditionalOnClass(Flyway.class)
    static class ModuleFlywayConfiguration {

        /**
         * Logs all {@link ModuleFlywayConfigurer} beans registered in the Spring context.
         *
         * <p>Each module repo creates its own {@code @Bean Flyway} that calls
         * {@link ModuleFlywayConfigurer#createFlyway}. This bean provides observability
         * (startup log) so operators can verify that all expected module schemas are registered.
         *
         * @param configurers all {@link ModuleFlywayConfigurer} beans in the context
         * @return a sentinel bean confirming registration was logged
         */
        @Bean
        public ModuleFlywayRegistrationLogger moduleFlywayRegistrationLogger(
                final ObjectProvider<ModuleFlywayConfigurer> configurers) {
            configurers.orderedStream().forEach(c ->
                    LOG.info("event=MODULE_FLYWAY_CONFIGURER_REGISTERED schema={} migrationsPath={}",
                            c.schema(), c.migrationsPath()));
            return new ModuleFlywayRegistrationLogger();
        }
    }

    /**
     * Sentinel record confirming that module Flyway configurers have been registered.
     *
     * <p>Acts as a Spring bean to materialise the registration step in the context graph,
     * making it inspectable and testable.
     */
    public record ModuleFlywayRegistrationLogger() {
    }
}
