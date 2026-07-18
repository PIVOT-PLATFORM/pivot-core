package fr.pivot;

import com.tngtech.archunit.core.domain.JavaClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * EN53.1 — Spring Modulith structural verification, added at the {@code pivot-core-app} module
 * boundary (see {@code app/pom.xml}'s {@code build-helper-maven-plugin} execution: this test
 * lives in {@code app/src/test/java}, a new, additional test source root, separate from the
 * legacy {@code src/test/java} tree the {@code testSourceDirectory} override points at).
 *
 * <h2>The base-package trap</h2>
 *
 * <p>{@link ApplicationModules#of(Class)} derives its module model from the direct sub-packages
 * of the package containing the given {@code @SpringBootApplication} class — here {@code
 * fr.pivot}, the package of {@link PivotBackendApplication}. By default this treats
 * <strong>every</strong> direct sub-package of {@code fr.pivot} as its own strictly-encapsulated
 * "application module" and enforces, for all of them pairwise: no cyclic dependencies, and no
 * reaching into a sibling module's non-published (non-API) types.
 *
 * <p>{@code fr.pivot} contains the new {@code agilite} module (this Enabler's actual subject —
 * {@code fr.pivot.agilite}), nine pre-existing shell packages ({@code config}, {@code auth},
 * {@code account}, {@code tenant}, {@code contact}, {@code notification}, {@code plan}, {@code
 * scheduler}, {@code modules}) that predate Spring Modulith entirely and were never designed
 * with its rules in mind, <strong>and</strong> — because {@code fr.pivot:pivot-core-starter}'s
 * classes share the same {@code fr.pivot} root package ({@code fr.pivot.core.*}) even though
 * they physically ship in a separate JAR/Maven module — the starter's own {@code fr.pivot.core}
 * package, which becomes yet another sibling "module" under the same base package. Running
 * {@link ApplicationModules#verify()} unfiltered against this codebase would fail immediately
 * on pre-existing shell-to-shell structural issues that have nothing to do with {@code agilite}
 * — and, empirically confirmed by inspecting {@code agilite}'s actual imports once that module
 * landed in this worktree, on {@code agilite}'s own legitimate, already-documented use of the
 * starter's published API: {@code fr.pivot.core.auth.AuthenticatedPrincipal}/{@code
 * AuthenticatedPrincipalResolver} and {@code fr.pivot.core.team.Team}/{@code TeamRepository}/
 * {@code TeamMemberRepository}. Spring Modulith's default encapsulation rule only treats types
 * residing directly in a module's root package as its public API — types in nested
 * sub-packages (like {@code core.auth} and {@code core.team}, both nested under {@code
 * fr.pivot.core}) are internal by default unless published via {@code @NamedInterface} or the
 * module is marked {@code @ApplicationModule(type = Type.OPEN)}. {@code pivot-core-starter}
 * already documents these exact packages as its intentionally exported surface (see {@code
 * PivotCoreAutoConfiguration}'s Javadoc "Exported packages" table and this repo's {@code
 * CLAUDE.md}) but does not yet encode that via Spring Modulith annotations — and {@code
 * starter/} is outside this task's file scope, so that cannot be fixed here. Without excluding
 * {@code fr.pivot.core..} too, this test would fail on exactly the dependency it is meant to
 * allow — the second trap this test is written to avoid, on top of the shell one.
 *
 * <h2>Approach retained</h2>
 *
 * <p>Both the shell packages and {@code fr.pivot.core..} are excluded from the Spring
 * Modulith/ArchUnit class universe entirely, via the {@code ignoredClasses} predicate overload
 * of {@link ApplicationModules#of(Class, com.tngtech.archunit.base.DescribedPredicate)} (the
 * exclusion-predicate approach — the alternative considered, marking each excluded package
 * {@code @ApplicationModule(type = Type.OPEN)} via its own {@code package-info.java}, was not
 * usable here: this task's file scope only allows adding a class under {@code fr.pivot.config},
 * not under the other eight shell packages nor under {@code starter/}'s {@code fr.pivot.core}).
 * {@code fr.pivot.agilite} is left as, in effect, the only recognised application module — it is
 * verified to have a consistent internal structure and (per Spring Modulith's default
 * encapsulation rule) that nothing outside it reaches into its non-published/internal types.
 *
 * <h2>Trade-off / CI risk — documented as instructed</h2>
 *
 * <p>Because the excluded packages are entirely invisible to the analysis, this test does
 * <strong>not</strong> currently catch: (a) a shell package illegally reaching into {@code
 * agilite}'s internals (the caller class itself is excluded, so ArchUnit has no record of the
 * reference), nor (b) {@code agilite} reaching into an excluded package's non-published types
 * (the callee class is excluded, so there is nothing to violate) — this specifically means
 * {@code agilite}'s use of {@code fr.pivot.core.auth}/{@code fr.pivot.core.team} is allowed
 * unconditionally by this test, trusting the starter's own documentation rather than an
 * enforced Modulith contract. What it does verify at minimum, per this Enabler's instructions,
 * is {@code agilite}'s own internal consistency as a self-contained application module — the
 * meaningful new-module-boundary check available given neither the shell nor the starter were
 * modularised with Spring Modulith annotations yet. As further business modules (pilotage,
 * collaboratif) are folded into this modulith in later EN53 vagues, and/or the shell packages
 * and {@code fr.pivot.core} are retrofitted with {@code @ApplicationModule}/{@code
 * @NamedInterface} declarations (keeping them in the graph, with {@code fr.pivot.core}'s actual
 * exported packages formally published instead of blanket-excluded), this exclusion list should
 * shrink and the verification should be tightened correspondingly — that retrofit was out of
 * scope for this task (file scope restricted to {@code fr.pivot.config} for shell/starter Java
 * changes). This could not be compiled or run locally (no local JDK 24 — see CLAUDE.md JDK gap);
 * CI is the source of truth for whether {@code verify()} actually passes as designed.
 */
class ModularityTests {

    /**
     * {@code fr.pivot} sub-packages excluded from the Spring Modulith module model — see class
     * Javadoc for why each group is excluded. ArchUnit package-matcher syntax: {@code ".."}
     * matches the package and all its sub-packages.
     */
    private static final String[] EXCLUDED_PACKAGES = {
            // Pre-existing shell packages — predate EN53.1, not designed as Modulith modules.
            "fr.pivot.config..",
            "fr.pivot.auth..",
            "fr.pivot.account..",
            "fr.pivot.tenant..",
            "fr.pivot.contact..",
            "fr.pivot.notification..",
            "fr.pivot.plan..",
            "fr.pivot.scheduler..",
            "fr.pivot.modules..",
            // pivot-core-starter's published API surface (separate Maven module/JAR, but same
            // fr.pivot root package) — its own sub-packages (core.auth, core.team, ...) would
            // otherwise be misclassified as "internal" by Modulith's default rule; see Javadoc.
            "fr.pivot.core..",
    };

    private static final ApplicationModules MODULES = ApplicationModules.of(
            PivotBackendApplication.class,
            JavaClass.Predicates.resideInAnyPackage(EXCLUDED_PACKAGES));

    @Test
    @DisplayName("agilite is a structurally consistent Spring Modulith application module "
            + "(shell + starter packages excluded — see class Javadoc for why and the risk)")
    void verifiesModularStructure() {
        MODULES.verify();
    }
}
