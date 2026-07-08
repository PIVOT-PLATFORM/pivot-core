# pivot-core-starter

Spring Boot auto-configuration library published by [pivot-core](https://github.com/PIVOT-PLATFORM/pivot-core)
and consumed by all `pivot-xxx-core` module repositories.

## Packages exported

State verified file by file (2026-07-08, `pivot-core#171`) — this table only lists what
actually ships in this module today, not the target architecture:

| Package | Content |
|---------|---------|
| `fr.pivot.core.tenant` | `TenantContext` only. `TenantContextHolder`/`@TenantAware` are deferred — no real consumer identified yet (see `PivotCoreAutoConfiguration` Javadoc) |
| `fr.pivot.core.team` | `Team`, `TeamMember` entities + repositories (public schema, cross-module). No REST API/service — none specified by any US yet |
| `fr.pivot.core.modules` | `PivotModule` interface, `ModuleRegistry`, `@RequiresModule` |
| `fr.pivot.core.db` | Flyway public schema baseline, `ModuleFlywayConfigurer` (multi-schema) |
| `fr.pivot.core.auth` | `AuthenticatedPrincipal` (record `userId`/`tenantId`/`role`) + `AuthenticatedPrincipalResolver` (raw token → minimal principal contract) — see below |

**`fr.pivot.core.auth` — minimal principal extracted (ADR-022), token validation itself not yet
duplicated.** `pivot-core#171` required two decisions before any code moved on this
security-critical component: the shape of a shared minimal principal, and "duplicated validation
(shared library) vs. centralized (network call)". [ADR-022](../../pivot-docs/docs/adr/ADR-022-principal-authentification-minimal-partage.md)
settles both: `AuthenticatedPrincipal(userId, tenantId, role)` — deliberately excludes every
profile field specific to `pivot-core-app` (email, password hash, 2FA/trusted-device state,
locale, avatar…) — and **duplicated validation via shared library code**, never a network call to
`pivot-core` (would contradict the fault-isolation goal already documented by every satellite
`CLAUDE.md`). `fr.pivot.auth.service.TokenService` (`pivot-core-app`) implements
`AuthenticatedPrincipalResolver`; `fr.pivot.notification.config.StompAuthChannelInterceptor` is
the first real consumer depending on the interface instead of the concrete `User` entity.

The validation logic itself (SHA-256 hash comparison, expiry, tenant-invalidation/user-deactivation
checks) is **not yet** duplicated into this starter — no `pivot-xxx-core` repo has a real consumer
for it today (all bootstrap-infrastructure only). Tracked as a dedicated follow-up once a module
repo needs to validate tokens locally against `public.access_tokens`. See the
`PivotCoreAutoConfiguration` Javadoc and ADR-022 for the full reasoning.

## Consuming the library in a module repo

### 1. Add the GitHub Packages repository to your `pom.xml`

```xml
<repositories>
    <repository>
        <id>github</id>
        <name>PIVOT GitHub Packages</name>
        <url>https://maven.pkg.github.com/PIVOT-PLATFORM/pivot-core</url>
    </repository>
</repositories>
```

### 2. Add the dependency

```xml
<dependency>
    <groupId>fr.pivot</groupId>
    <artifactId>pivot-core-starter</artifactId>
    <version>0.26.0</version>
</dependency>
```

### 3. Configure Maven settings for authentication

GitHub Packages requires authentication. Add the following to your `~/.m2/settings.xml`:

```xml
<servers>
    <server>
        <id>github</id>
        <username>${env.GITHUB_ACTOR}</username>
        <password>${env.GITHUB_TOKEN}</password>
    </server>
</servers>
```

In CI (GitHub Actions), use `actions/setup-java` with `server-id: github`:

```yaml
- uses: actions/setup-java@v4
  with:
    java-version: '25'
    distribution: 'temurin'
    server-id: github
    server-username: GITHUB_ACTOR
    server-password: GITHUB_TOKEN
```

### 4. Auto-configuration

The starter registers its auto-configuration automatically via
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
No `@Import` or `@ComponentScan` is needed in your module repo.

## Multi-schema Flyway convention (EN17.4)

Each `pivot-xxx-core` module manages its own PostgreSQL schema via Flyway.
Declare a `ModuleFlywayConfigurer` bean:

```java
@Configuration
public class PilotageFlywayConfig {

    @Bean
    public ModuleFlywayConfigurer pilotageFlywayConfigurer() {
        // schema: dedicated PostgreSQL schema for this module
        // migrationsPath: Flyway location of migration scripts
        return new ModuleFlywayConfigurer("pilotage", "classpath:db/pilotage");
    }
}
```

`ModuleFlywayConfigurer` implements `FlywayConfigurationCustomizer` and is automatically
picked up by Spring Boot's Flyway auto-configuration.

### Cross-schema FK convention

Cross-schema foreign keys are allowed **only** toward:
- `public.teams(id)`
- `public.tenants(id)`

Module schemas must **never** write to the `public` schema.

See [docs/architecture/bdd-multi-schema.md](../../pivot-docs/docs/architecture/bdd-multi-schema.md)
for the full convention and SQL template.

### Bootstrap SQL template

A `V1__init_{schema}.sql` template is available in
`pivot-docs/docs/architecture/` — copy and adapt it for each new module schema.

## Versioning

This library follows [Semantic Versioning](https://semver.org/) managed by
[semantic-release](https://semantic-release.gitbook.io/semantic-release/).
Published to GitHub Packages on every tagged release.

## Building locally

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

# From pivot-core root — builds both starter and app
./mvnw package -DskipTests

# Run starter tests only (includes Testcontainers integration tests)
./mvnw test -pl starter
```
