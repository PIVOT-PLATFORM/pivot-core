# Changelog

All notable changes to PIVOT Core are documented in this file.

**Versioning :** [Semantic Versioning](https://semver.org/spec/v2.0.0.html) — automated via [Semantic Release](https://github.com/semantic-release/semantic-release) on push to `main`.

---

## [0.0.0] - 2026-06-28

### Features

* **auth:** registration, login, email verification, resend verification, password reset, device confirmation (OTP email)
* **auth:** opaque session tokens — 256-bit SecureRandom, SHA-256 stored in DB, TTL configurable per tenant via `feature_flags`
* **auth:** token rotation with grace window for concurrent in-flight requests
* **auth:** multi-session management — max sessions per user enforced, oldest evicted on overflow
* **auth:** rate limiting — sliding-window Redis buckets per IP, per email, per device OTP
* **auth:** anti-enumeration — register / forgot-password / resend-verification always return 200
* **auth:** device trust — TOTP OTP email on unrecognized device, configurable TTL, sliding window
* **auth:** remember-me — extended TTL configurable via `SESSION_TTL_REMEMBER_ME_SECONDS`
* **email:** transactional emails with Thymeleaf + Spring MessageSource (fr/en) — welcome, verify, resend, reset-password, password-changed, account-exists, device-confirm, verify-reminder
* **email:** async sending (`@Async`), configurable date format via i18n bundle, locale fallback to `fr`
* **modules:** `PivotModule` contract, module registry, `ApplicationEventPublisher` bus
* **security:** Spring Security 7, `TokenAuthenticationFilter`, `@PreAuthorize`, CORS configured
* **db:** PostgreSQL 18 schema — `users`, `access_tokens`, `trusted_devices`, `password_reset_tokens`, `device_verify_tokens`, `tenants`, `feature_flags` (Flyway V1)
* **infra:** Docker Compose — PostgreSQL, Redis, Mailpit, Spring Boot app
* **ci:** GitHub Actions — build, tests (JUnit 5 + Testcontainers), Checkstyle, SpotBugs, SonarCloud
* **ci:** DAST, SCA (Trivy + Dependabot), SBOM CycloneDX, secret scanning (Gitleaks), SAST (CodeQL + Semgrep), SLSA L3, OpenSSF Scorecard, Plumber compliance
