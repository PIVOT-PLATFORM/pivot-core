# 1.0.0 (2026-06-28)


### Bug Fixes

* **backend:** redis env var relaxed binding (Spring Boot 4.x) ([#82](https://github.com/PIVOT-PLATFORM/pivot-core/issues/82)) ([ec044e3](https://github.com/PIVOT-PLATFORM/pivot-core/commit/ec044e352a7ae731c3ec1db9a15f53fac9437cc1))
* **ci:** retire cache npm sur prepare (pivot-core pas de package-lock.json) ([#83](https://github.com/PIVOT-PLATFORM/pivot-core/issues/83)) ([9f29ff6](https://github.com/PIVOT-PLATFORM/pivot-core/commit/9f29ff691f379a28bb27b126322be51cd62646b8))
* **ci:** SLSA [@v2](https://github.com/v2).1.0 — detect-env sort SHA brut, builder-fetch.sh requiert refs/tags/ ([#84](https://github.com/PIVOT-PLATFORM/pivot-core/issues/84)) ([4770b8b](https://github.com/PIVOT-PLATFORM/pivot-core/commit/4770b8bbbca6ac53f7761fcff5481e1a3a214fa6))
* **docker:** apk upgrade --no-cache — CVE-2026-2100 p11-kit HIGH ([#88](https://github.com/PIVOT-PLATFORM/pivot-core/issues/88)) ([e22eacc](https://github.com/PIVOT-PLATFORM/pivot-core/commit/e22eacc7361a14a7f148f383e3248421db8ee978))
* **security:** corrige les 5 défauts P0 du scaffold ([d0b04ab](https://github.com/PIVOT-PLATFORM/pivot-core/commit/d0b04ab23ce2892f008f315a628b1d9af84d983f))


### Features

* **auth:** auth module MVP — E01 ([#105](https://github.com/PIVOT-PLATFORM/pivot-core/issues/105)) ([fc82e9b](https://github.com/PIVOT-PLATFORM/pivot-core/commit/fc82e9b4cd7d6511d86a0744e9dbe160efa6af4e))
* **backend:** i18n emails transactionnels (fr + en) ([#107](https://github.com/PIVOT-PLATFORM/pivot-core/issues/107)) ([2a08125](https://github.com/PIVOT-PLATFORM/pivot-core/commit/2a08125fb65ca2dea2046c9dcfa53b600f7df887))

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
