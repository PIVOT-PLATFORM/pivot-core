# [0.12.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.11.0...v0.12.0) (2026-07-05)


### Features

* **api:** active sessions self-service - GET/DELETE /api/account/sessions (US02.2.3) ([#132](https://github.com/PIVOT-PLATFORM/pivot-core/issues/132)) ([0ed8585](https://github.com/PIVOT-PLATFORM/pivot-core/commit/0ed8585e2edf6ddab732dd6f44dcccc9a98f503d)), closes [TokenService#validate](https://github.com/TokenService/issues/validate)

# [0.11.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.10.0...v0.11.0) (2026-07-05)


### Features

* **api:** US02.2.2 - Changer son e-mail ([#131](https://github.com/PIVOT-PLATFORM/pivot-core/issues/131)) ([0c7bb76](https://github.com/PIVOT-PLATFORM/pivot-core/commit/0c7bb7642a03c830a53817b701ec06dc70482c2e)), closes [PasswordEncoder#matches](https://github.com/PasswordEncoder/issues/matches) [EmailChangeService#confirmEmailChange](https://github.com/EmailChangeService/issues/confirmEmailChange) [#128](https://github.com/PIVOT-PLATFORM/pivot-core/issues/128)

# [0.10.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.9.0...v0.10.0) (2026-07-05)


### Features

* **api:** voir et éditer son profil (US02.1.1) ([#129](https://github.com/PIVOT-PLATFORM/pivot-core/issues/129)) ([08416bd](https://github.com/PIVOT-PLATFORM/pivot-core/commit/08416bd09297e70dea1cf74d0ff044152e200d38)), closes [ProfileService#updateAvatar](https://github.com/ProfileService/issues/updateAvatar) [#128](https://github.com/PIVOT-PLATFORM/pivot-core/issues/128)

# [0.9.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.8.0...v0.9.0) (2026-07-05)


### Features

* **api:** US02.2.1 - Changer son mot de passe ([#128](https://github.com/PIVOT-PLATFORM/pivot-core/issues/128)) ([1e149d7](https://github.com/PIVOT-PLATFORM/pivot-core/commit/1e149d71cdaccf1a80c5b1abe1afc1d1b9718997))

# [0.8.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.7.0...v0.8.0) (2026-07-05)


### Features

* **api:** admin liste les utilisateurs de son tenant (US06.1.1) ([#127](https://github.com/PIVOT-PLATFORM/pivot-core/issues/127)) ([283be5a](https://github.com/PIVOT-PLATFORM/pivot-core/commit/283be5a45de7775e4c0d48220948f9911318e2a7)), closes [AdminUserService#validateRole](https://github.com/AdminUserService/issues/validateRole)

# [0.7.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.6.0...v0.7.0) (2026-07-05)


### Features

* **api:** GET /api/superadmin/tenants — super admin liste tous les tenants (US06.2.3) ([#126](https://github.com/PIVOT-PLATFORM/pivot-core/issues/126)) ([bb7a418](https://github.com/PIVOT-PLATFORM/pivot-core/commit/bb7a418b43e38c7811a9c2628ca76a21930d5ae8)), closes [#1](https://github.com/PIVOT-PLATFORM/pivot-core/issues/1) [#2](https://github.com/PIVOT-PLATFORM/pivot-core/issues/2) [#1](https://github.com/PIVOT-PLATFORM/pivot-core/issues/1)

# [0.6.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.5.0...v0.6.0) (2026-07-03)


### Features

* **api:** EN03.2/US03.2.2 - GET /api/modules/{id}/status ([#123](https://github.com/PIVOT-PLATFORM/pivot-core/issues/123)) ([0b359d9](https://github.com/PIVOT-PLATFORM/pivot-core/commit/0b359d9ca1f943eba9431e3ec1118d7e329e4b18)), closes [pivot-ui#66](https://github.com/pivot-ui/issues/66)

# [0.5.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.4.1...v0.5.0) (2026-07-03)


### Features

* **api:** admin active/desactive les modules PIVOT par tenant (US03.1.1, US03.1.2) ([#122](https://github.com/PIVOT-PLATFORM/pivot-core/issues/122)) ([3c68345](https://github.com/PIVOT-PLATFORM/pivot-core/commit/3c683454d206375facb9cbc2eca13f9e3b4593e8))

## [0.4.1](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.4.0...v0.4.1) (2026-07-03)


### Bug Fixes

* **docs:** corrige la section PATCH_NOTES mal placée pour US01.2.4 ([#125](https://github.com/PIVOT-PLATFORM/pivot-core/issues/125)) ([a7856b2](https://github.com/PIVOT-PLATFORM/pivot-core/commit/a7856b2626d6c80651ccefed9e51a371df69a3f0)), closes [#120](https://github.com/PIVOT-PLATFORM/pivot-core/issues/120) [#120](https://github.com/PIVOT-PLATFORM/pivot-core/issues/120) [#120](https://github.com/PIVOT-PLATFORM/pivot-core/issues/120)

# [0.4.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.3.0...v0.4.0) (2026-07-03)


### Features

* **auth:** politique de robustesse du mot de passe configurable (US01.2.4) ([#120](https://github.com/PIVOT-PLATFORM/pivot-core/issues/120)) ([2276c6d](https://github.com/PIVOT-PLATFORM/pivot-core/commit/2276c6d4b32ba35145db8e4cd204b5ace71d9cff))

# [0.3.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.2.0...v0.3.0) (2026-07-03)


### Features

* **modules:** EN03.3 — Cache Redis statut modules TTL 60s ([#121](https://github.com/PIVOT-PLATFORM/pivot-core/issues/121)) ([8f15f09](https://github.com/PIVOT-PLATFORM/pivot-core/commit/8f15f093e475af212f456799f40be91d18592093))

# [0.2.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.1.0...v0.2.0) (2026-07-03)


### Features

* **modules:** EN03.1 — PivotModule interface + registre backend ([#119](https://github.com/PIVOT-PLATFORM/pivot-core/issues/119)) ([4bd4334](https://github.com/PIVOT-PLATFORM/pivot-core/commit/4bd4334bf2f2756331e0baf5234d6fbff1c6c90e)), closes [#2](https://github.com/PIVOT-PLATFORM/pivot-core/issues/2)

# [0.1.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.0.0...v0.1.0) (2026-06-28)


### Features

* **api:** GET /api/modules — registre des modules PIVOT (EN03.4) ([#111](https://github.com/PIVOT-PLATFORM/pivot-core/issues/111)) ([b3e696a](https://github.com/PIVOT-PLATFORM/pivot-core/commit/b3e696a33ed56a6107f4ab23ddcb1e22d8227683))

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
