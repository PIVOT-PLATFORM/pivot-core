package fr.pivot.config;

import fr.pivot.core.db.ModuleFlywayConfigurer;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * EN53.1/EN53.2 — fait cohabiter le schéma {@code public} (shell), le schéma {@code agilite}
 * (module absorbé, Vague 1) et le schéma {@code collaboratif} (module absorbé, Vague 2) comme
 * autant de migrations Flyway distinctes dans le même contexte Spring de l'app agrégée.
 *
 * <h2>Pourquoi ce design (et pas un second bean {@link Flyway})</h2>
 *
 * <p>Spring Boot n'auto-configure son bean {@code flyway} (schéma {@code public},
 * {@code classpath:db/migration/public}) que via {@code @ConditionalOnMissingBean(Flyway.class)}.
 * Déclarer un <em>bean</em> {@link Flyway} supplémentaire pour {@code agilite} ou {@code
 * collaboratif} <strong>supprime</strong> donc entièrement le bean public de Boot — le schéma
 * {@code public} n'est alors jamais migré, et la migration du module (dont la FK pointe vers
 * {@code public.tenants}) échoue avec {@code relation "public.tenants" does not exist} (constaté
 * en test local, EN53.1).
 *
 * <p>Ce config ne déclare donc <strong>aucun</strong> bean {@link Flyway} : Boot garde la main sur
 * la migration du schéma {@code public}, exécutée pendant le refresh du contexte (via son
 * {@code FlywayMigrationInitializer}, avant l'{@code EntityManagerFactory}) exactement comme avant
 * l'absorption des modules. Les migrations {@code agilite} et {@code collaboratif} sont déclenchées
 * par les {@link ApplicationRunner}s ci-dessous, <strong>après</strong> le refresh (donc après que
 * {@code public.tenants}/{@code teams} existent) — sûr car {@code spring.jpa.hibernate.ddl-auto=none}
 * dans l'app agrégée (aucune validation de schéma à l'init de l'EMF). Chaque Flyway de module est
 * construit <em>inline</em> (jamais exposé en bean) précisément pour ne pas retomber dans la
 * suppression du bean public de Boot.
 *
 * <p>Les contextes de test isolés de chaque module ({@code AgiliteTestApplication}, {@code
 * CollaboratifTestApplication} ou équivalent) n'ont pas cette classe (elle est sous {@code
 * fr.pivot.config}, shell) et migrent leur propre schéma via leur propre {@code
 * spring.flyway.locations} — ce config ne concerne que l'app agrégée.
 */
@Configuration
public class ModuleFlywayMigrationConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleFlywayMigrationConfig.class);

    /**
     * Migre le schéma {@code agilite} après le refresh du contexte (le schéma {@code public} a déjà
     * été migré par Boot pendant le refresh). Idempotent — rejoué sans effet si déjà à jour (utile
     * avec le conteneur Postgres singleton partagé entre contextes de test).
     *
     * @param dataSource la {@link DataSource} applicative partagée
     * @return le runner déclenchant la migration du schéma agilite
     */
    @Bean
    public ApplicationRunner agiliteSchemaFlywayMigrationRunner(final DataSource dataSource) {
        return args -> {
            LOG.info("event=MODULE_SCHEMA_FLYWAY_MIGRATE_START schema=agilite");
            final Flyway agiliteFlyway =
                    new ModuleFlywayConfigurer("agilite", "classpath:db/migration/agilite")
                            .createFlyway(dataSource);
            selfHealAndMigrate(agiliteFlyway);
            LOG.info("event=MODULE_SCHEMA_FLYWAY_MIGRATE_DONE schema=agilite");
        };
    }

    /**
     * Migre le schéma {@code collaboratif} après le refresh du contexte (EN53.2 Vague 2 — même
     * raisonnement que {@link #agiliteSchemaFlywayMigrationRunner}, voir Javadoc de classe).
     * Idempotent — rejoué sans effet si déjà à jour (utile avec le conteneur Postgres singleton
     * partagé entre contextes de test).
     *
     * @param dataSource la {@link DataSource} applicative partagée
     * @return le runner déclenchant la migration du schéma collaboratif
     */
    @Bean
    public ApplicationRunner collaboratifSchemaFlywayMigrationRunner(final DataSource dataSource) {
        return args -> {
            LOG.info("event=MODULE_SCHEMA_FLYWAY_MIGRATE_START schema=collaboratif");
            final Flyway collaboratifFlyway =
                    new ModuleFlywayConfigurer("collaboratif", "classpath:db/migration/collaboratif")
                            .createFlyway(dataSource);
            selfHealAndMigrate(collaboratifFlyway);
            LOG.info("event=MODULE_SCHEMA_FLYWAY_MIGRATE_DONE schema=collaboratif");
        };
    }

    /**
     * Repairs the module schema's Flyway history, then migrates.
     *
     * <p>EN53 — with the pre-BETA « fichier V1 unique » convention (pivot-core CLAUDE.md), the
     * single {@code V1__schema_init.sql} of a module keeps changing as schema evolves. A
     * long-lived database (recette carried its {@code collaboratif} schema over from the module's
     * standalone-service era) therefore stores an <em>older</em> V1 checksum than the one this
     * build resolves, and Flyway's {@code validate} (run implicitly by {@code migrate}) aborts
     * with {@code FlywayValidateException: checksum mismatch}. {@link Flyway#repair()} realigns the
     * stored checksums (and clears any failed entry) so {@code migrate()} then proceeds. Together
     * with {@code ignoreMigrationPatterns("*:missing")} (see {@link ModuleFlywayConfigurer}) this
     * lets module migrations self-heal against a persistent DB while the schema is still mutable.
     *
     * <p>{@code repair()} only touches the {@code flyway_schema_history} bookkeeping — it never
     * re-runs or drops applied SQL — so it is a safe no-op when history is already consistent
     * (fresh DBs, CI, tests). Once the schema stabilises at BETA (numbered immutable migrations),
     * checksums no longer drift and repair becomes a pure no-op.
     *
     * @param flyway the module-schema {@link Flyway} instance to repair then migrate
     */
    private static void selfHealAndMigrate(final Flyway flyway) {
        flyway.repair();
        flyway.migrate();
    }
}
