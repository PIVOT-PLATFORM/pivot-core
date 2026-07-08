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

**`fr.pivot.core.auth` — not yet extracted.** Escalated on `pivot-core#171`: the opaque-token
validation path (`TokenService`/`TokenAuthenticationFilter`) is hard-coupled to the concrete
`fr.pivot.auth.entity.User` JPA entity, so a mechanical move is not safe — a clean extraction
requires a new shared minimal-principal abstraction, which is an architecture decision on a
security-critical component, not a unilateral agent call. See the `PivotCoreAutoConfiguration`
Javadoc for the full reasoning.

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
