# [0.28.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.27.1...v0.28.0) (2026-07-09)


### Bug Fixes

* **build:** mark starter's Spring Security deps optional ([#211](https://github.com/PIVOT-PLATFORM/pivot-core/issues/211)) ([b11d665](https://github.com/PIVOT-PLATFORM/pivot-core/commit/b11d6651f876180a6bf911e922ce33f65c410bfc))
* **modules:** Dette S2 — raccorder le cache Redis EN03.3 au chemin de lecture du statut module ([#196](https://github.com/PIVOT-PLATFORM/pivot-core/issues/196)) ([b172b89](https://github.com/PIVOT-PLATFORM/pivot-core/commit/b172b890252cdd06488e4e48660b8d828ee98f29)), closes [#195](https://github.com/PIVOT-PLATFORM/pivot-core/issues/195)


### Features

* **infra:** EN07.4 — PgBouncer session mode connection pooler (prod) ([#197](https://github.com/PIVOT-PLATFORM/pivot-core/issues/197)) ([6eb8bb8](https://github.com/PIVOT-PLATFORM/pivot-core/commit/6eb8bb8cb094f4e11f77658d462b0f078cb7dd35)), closes [#185](https://github.com/PIVOT-PLATFORM/pivot-core/issues/185)

## [0.27.1](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.27.0...v0.27.1) (2026-07-09)


### Bug Fixes

* **ci:** publish root aggregator POM alongside starter/app JARs ([#209](https://github.com/PIVOT-PLATFORM/pivot-core/issues/209)) ([6196f67](https://github.com/PIVOT-PLATFORM/pivot-core/commit/6196f675d9a48a1e09ad9bc5ccdd5dd6ff4e7be0))

# [0.27.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.26.0...v0.27.0) (2026-07-09)


### Bug Fixes

* **api:** double préfixe /api/api sur 6 contrôleurs REST ([#182](https://github.com/PIVOT-PLATFORM/pivot-core/issues/182)) ([2f93033](https://github.com/PIVOT-PLATFORM/pivot-core/commit/2f930333dc0f4627b09e8885a894766146ad4e8b)), closes [#181](https://github.com/PIVOT-PLATFORM/pivot-core/issues/181)
* **backend:** enable SMTP STARTTLS/auth in production profile ([#199](https://github.com/PIVOT-PLATFORM/pivot-core/issues/199)) ([660cc0e](https://github.com/PIVOT-PLATFORM/pivot-core/commit/660cc0ee0d65b68bf5a222363016b05e73c04670))
* **ci:** docker login to GHCR before pull in deploy.yml ([#200](https://github.com/PIVOT-PLATFORM/pivot-core/issues/200)) ([659771e](https://github.com/PIVOT-PLATFORM/pivot-core/commit/659771e9fbda41a572100efbe938f2c491f6cac8))
* **ci:** GHCR image path doubled the repo segment ([#198](https://github.com/PIVOT-PLATFORM/pivot-core/issues/198)) ([657ee63](https://github.com/PIVOT-PLATFORM/pivot-core/commit/657ee631d92d0a33d19354fa99fe2fdcd4ef21ad))
* **ci:** require exact-line match for the release trigger, not substring ([#164](https://github.com/PIVOT-PLATFORM/pivot-core/issues/164)) ([4a84aeb](https://github.com/PIVOT-PLATFORM/pivot-core/commit/4a84aeb1a1cf2b368b83cfa1cc950a91f336ed07))
* **deps:** bump logback-core to 1.5.35 (CVE-2026-10532) ([#208](https://github.com/PIVOT-PLATFORM/pivot-core/issues/208)) ([c2925cd](https://github.com/PIVOT-PLATFORM/pivot-core/commit/c2925cd7d6506a234d41a5ad120cbbc37b8e5fc0))
* **infra:** compose dev — alias réseau pivot-core + montage nginx.dev.conf ([#175](https://github.com/PIVOT-PLATFORM/pivot-core/issues/175)) ([aeaf6d3](https://github.com/PIVOT-PLATFORM/pivot-core/commit/aeaf6d364eafc8aa0d1d48aed714cd709b6dd783)), closes [#174](https://github.com/PIVOT-PLATFORM/pivot-core/issues/174)
* **modules:** aligner le champ description API modules avec PivotModule ([#184](https://github.com/PIVOT-PLATFORM/pivot-core/issues/184)) ([66f6bb1](https://github.com/PIVOT-PLATFORM/pivot-core/commit/66f6bb15204718477838afff88e4207ac0dac45a)), closes [#183](https://github.com/PIVOT-PLATFORM/pivot-core/issues/183)
* **modules:** module registry always empty — cross-service auto-discovery is impossible ([#178](https://github.com/PIVOT-PLATFORM/pivot-core/issues/178)) ([c76b1bf](https://github.com/PIVOT-PLATFORM/pivot-core/commit/c76b1bf7e43ea17a37c9b1b1512009f785d3bc00)), closes [pivot-ui#118](https://github.com/pivot-ui/issues/118)


### Features

* **auth:** EN17.1 — principal d'authentification minimal partagé (ADR-022) ([#180](https://github.com/PIVOT-PLATFORM/pivot-core/issues/180)) ([00ccb07](https://github.com/PIVOT-PLATFORM/pivot-core/commit/00ccb071c9c08a65dc88922bd56e0fa7cb5e1ebe)), closes [#171](https://github.com/PIVOT-PLATFORM/pivot-core/issues/171) [#171](https://github.com/PIVOT-PLATFORM/pivot-core/issues/171)
* **db:** EN17.1 — extraire fr.pivot.core.modules + TenantContext vers pivot-core-starter ([#173](https://github.com/PIVOT-PLATFORM/pivot-core/issues/173)) ([124dbb9](https://github.com/PIVOT-PLATFORM/pivot-core/commit/124dbb97c71d22a70c2c3ad05bf7a3696ff4fc90)), closes [#172](https://github.com/PIVOT-PLATFORM/pivot-core/issues/172)
* **db:** EN17.1 + EN17.4 — pivot-core-starter multi-module + ModuleFlywayConfigurer ([#167](https://github.com/PIVOT-PLATFORM/pivot-core/issues/167)) ([eacd8ed](https://github.com/PIVOT-PLATFORM/pivot-core/commit/eacd8edb9fbc07e78543a9845412a96456065d2c))
* **infra:** EN07.3 — ActiveMQ Classic broker (KahaDB, per-domain DLQ) ([#193](https://github.com/PIVOT-PLATFORM/pivot-core/issues/193)) ([286daea](https://github.com/PIVOT-PLATFORM/pivot-core/commit/286daea2f421048d5d6c0cc012eed9174bfcee6b)), closes [#192](https://github.com/PIVOT-PLATFORM/pivot-core/issues/192)
* **infra:** EN17.9 — modules satellites dans le compose dev ([#179](https://github.com/PIVOT-PLATFORM/pivot-core/issues/179)) ([f0ff86b](https://github.com/PIVOT-PLATFORM/pivot-core/commit/f0ff86b82e28bb6a05e47adf661e49c30f086e0f))

# [0.26.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.25.0...v0.26.0) (2026-07-06)


### Features

* **auth:** alerte connexion suspecte — appareil inconnu (US01.4.3a) ([#151](https://github.com/PIVOT-PLATFORM/pivot-core/issues/151)) ([2794425](https://github.com/PIVOT-PLATFORM/pivot-core/commit/27944259c3555102635264992e4d68a1a432f323)), closes [SessionService#login](https://github.com/SessionService/issues/login)
* **auth:** gestion des appareils de confiance (US01.4.2) ([#152](https://github.com/PIVOT-PLATFORM/pivot-core/issues/152)) ([628d9cf](https://github.com/PIVOT-PLATFORM/pivot-core/commit/628d9cff52cf7c0709779b4d60838596deb83837))
* **auth:** US01.5.1 - email de confirmation d'action sensible ([#154](https://github.com/PIVOT-PLATFORM/pivot-core/issues/154)) ([8fccf62](https://github.com/PIVOT-PLATFORM/pivot-core/commit/8fccf6204186dbfe3aa8eed64ceae3ee554c018b))
* **backend:** EN04.4 — Docker healthchecks liveness/readiness ([#162](https://github.com/PIVOT-PLATFORM/pivot-core/issues/162)) ([a6d6e95](https://github.com/PIVOT-PLATFORM/pivot-core/commit/a6d6e95ed092325708c01f732bd516baeb57a6a5))
* **infra:** add docker-compose.prod.yml (EN07.1) ([#149](https://github.com/PIVOT-PLATFORM/pivot-core/issues/149)) ([92e3e43](https://github.com/PIVOT-PLATFORM/pivot-core/commit/92e3e43c0fa0fc2e9955cc1d0fad23263ed8e123))
* **notifications:** infrastructure notifications in-app (EN-NOTIF) ([#160](https://github.com/PIVOT-PLATFORM/pivot-core/issues/160)) ([db58eae](https://github.com/PIVOT-PLATFORM/pivot-core/commit/db58eaeffe7f863f0b561ec781a3f9eb9ee73606)), closes [154/#151](https://github.com/PIVOT-PLATFORM/pivot-core/issues/151) [ChannelInterceptor#preSend](https://github.com/ChannelInterceptor/issues/preSend)
* **US03.3.3:** Admin tenant voit uniquement modules de son plan ([#161](https://github.com/PIVOT-PLATFORM/pivot-core/issues/161)) ([130f263](https://github.com/PIVOT-PLATFORM/pivot-core/commit/130f2637dd817ad8fd5be74e4128e991b5092f8e)), closes [#153](https://github.com/PIVOT-PLATFORM/pivot-core/issues/153) [#159](https://github.com/PIVOT-PLATFORM/pivot-core/issues/159) [#153](https://github.com/PIVOT-PLATFORM/pivot-core/issues/153)

# [0.24.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.23.0...v0.24.0) (2026-07-06)


### Features

* **US03.3.2:** SUPER_ADMIN active/désactive un module par tenant (override) ([#159](https://github.com/PIVOT-PLATFORM/pivot-core/issues/159)) ([a0cfa18](https://github.com/PIVOT-PLATFORM/pivot-core/commit/a0cfa1810ec7e71c0e690031bd624584ced32db3)), closes [ModuleActivationService#isEnabled](https://github.com/ModuleActivationService/issues/isEnabled)

# [0.23.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.22.0...v0.23.0) (2026-07-06)


### Features

* **US03.3.1:** SUPER_ADMIN définit modules disponibles par plan ([#153](https://github.com/PIVOT-PLATFORM/pivot-core/issues/153)) ([0ce37be](https://github.com/PIVOT-PLATFORM/pivot-core/commit/0ce37be8d13728056aea54cb1fb9c4e3709c8b28)), closes [SuperAdminTenantController#updateStatus](https://github.com/SuperAdminTenantController/issues/updateStatus) [PlanService#getModules](https://github.com/PlanService/issues/getModules) [#161](https://github.com/PIVOT-PLATFORM/pivot-core/issues/161)

# [0.22.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.21.0...v0.22.0) (2026-07-06)


### Features

* **backend:** EN04.2 - endpoints Spring Actuator sur port de management séparé ([#158](https://github.com/PIVOT-PLATFORM/pivot-core/issues/158)) ([74d2621](https://github.com/PIVOT-PLATFORM/pivot-core/commit/74d26216982f5e01d25bc05505d4b5a997b11367)), closes [pivot-core#149](https://github.com/pivot-core/issues/149)
* **observability:** EN04.3 - export Prometheus via Micrometer ([#157](https://github.com/PIVOT-PLATFORM/pivot-core/issues/157)) ([7888839](https://github.com/PIVOT-PLATFORM/pivot-core/commit/7888839e1aa6d7a9af4fd34e1aebc4ea03eb7459)), closes [ModuleActivationService#activate](https://github.com/ModuleActivationService/issues/activate) [#149](https://github.com/PIVOT-PLATFORM/pivot-core/issues/149)

# [0.21.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.20.0...v0.21.0) (2026-07-06)


### Features

* **backend:** EN04.1 — logs structurés JSON + MDC ([#156](https://github.com/PIVOT-PLATFORM/pivot-core/issues/156)) ([a73e785](https://github.com/PIVOT-PLATFORM/pivot-core/commit/a73e785e38edd4fdbc6f1c01491b7e475adf295a))

# [0.20.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.19.1...v0.20.0) (2026-07-06)


### Features

* **config:** EN07.2 - secret management via Docker secrets ([#150](https://github.com/PIVOT-PLATFORM/pivot-core/issues/150)) ([3d3aaa5](https://github.com/PIVOT-PLATFORM/pivot-core/commit/3d3aaa5c9d74f6a9b6a99b6b5761e0a487e28fac)), closes [#149](https://github.com/PIVOT-PLATFORM/pivot-core/issues/149)

## [0.19.1](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.19.0...v0.19.1) (2026-07-06)


### Bug Fixes

* **docs:** étend l'Autoloop PR à toutes les branches, pas seulement feat/{us-id} ([#147](https://github.com/PIVOT-PLATFORM/pivot-core/issues/147)) ([04519ba](https://github.com/PIVOT-PLATFORM/pivot-core/commit/04519ba9f1e9e3bbc0b8e732e739b975dfe6827f)), closes [chore#146](https://github.com/chore/issues/146)

# [0.19.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.18.0...v0.19.0) (2026-07-06)


### Features

* suppression de compte RGPD Art.17 (US02.2.4) ([#140](https://github.com/PIVOT-PLATFORM/pivot-core/issues/140)) ([8522c92](https://github.com/PIVOT-PLATFORM/pivot-core/commit/8522c92032327cff467cc5190897cbf217da66dc)), closes [User#anonymizedAt](https://github.com/User/issues/anonymizedAt) [CryptoUtils#resolveOtpSecret](https://github.com/CryptoUtils/issues/resolveOtpSecret) [PasswordEncoder#matches](https://github.com/PasswordEncoder/issues/matches) [PasswordService#resetPassword](https://github.com/PasswordService/issues/resetPassword) [EmailChangeService#confirmEmailChange](https://github.com/EmailChangeService/issues/confirmEmailChange) [AccountDeletionService#cancelDeletion](https://github.com/AccountDeletionService/issues/cancelDeletion) [PasswordService#RESET_MAX](https://github.com/PasswordService/issues/RESET_MAX)

# [0.18.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.17.0...v0.18.0) (2026-07-06)


### Features

* **api:** admin désactive/réactive un compte utilisateur (US06.1.4/US06.1.5) ([#142](https://github.com/PIVOT-PLATFORM/pivot-core/issues/142)) ([351cc61](https://github.com/PIVOT-PLATFORM/pivot-core/commit/351cc618e26ecde49c243fd1f7886fc0a01f42db)), closes [TokenService#validate](https://github.com/TokenService/issues/validate) [#141](https://github.com/PIVOT-PLATFORM/pivot-core/issues/141) [AdminUserService#updateStatus](https://github.com/AdminUserService/issues/updateStatus)

# [0.17.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.16.1...v0.17.0) (2026-07-06)


### Features

* **api:** admin modifie le rôle d'un utilisateur (US06.1.3) ([#141](https://github.com/PIVOT-PLATFORM/pivot-core/issues/141)) ([01df08a](https://github.com/PIVOT-PLATFORM/pivot-core/commit/01df08a69214cf5bab297c9f5b4e645517c71d09)), closes [AdminUserService#updateRole](https://github.com/AdminUserService/issues/updateRole)

## [0.16.1](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.16.0...v0.16.1) (2026-07-05)


### Bug Fixes

* **backend:** log injection ModuleActivationService + bump pom.xml à la release (SonarCloud new-code figé) ([#138](https://github.com/PIVOT-PLATFORM/pivot-core/issues/138)) ([6bab7bd](https://github.com/PIVOT-PLATFORM/pivot-core/commit/6bab7bdd7673c6fe3713e5a3fe9ae679366cd321)), closes [TokenService#validate](https://github.com/TokenService/issues/validate)

# [0.16.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.15.0...v0.16.0) (2026-07-05)


### Features

* **api:** préférence de langue du profil (US02.1.2) ([#130](https://github.com/PIVOT-PLATFORM/pivot-core/issues/130)) ([aec73cf](https://github.com/PIVOT-PLATFORM/pivot-core/commit/aec73cfdac0e79c5b0f7c22401e99cdf8b5b1077)), closes [#1](https://github.com/PIVOT-PLATFORM/pivot-core/issues/1) [#2](https://github.com/PIVOT-PLATFORM/pivot-core/issues/2) [AccountController#asString](https://github.com/AccountController/issues/asString) [#128](https://github.com/PIVOT-PLATFORM/pivot-core/issues/128)

# [0.15.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.14.0...v0.15.0) (2026-07-05)


### Features

* **api:** super admin désactive un tenant (US06.2.2) ([#135](https://github.com/PIVOT-PLATFORM/pivot-core/issues/135)) ([e1a6155](https://github.com/PIVOT-PLATFORM/pivot-core/commit/e1a61559aadfa4550faa4e9bfa715b6ee8023f51)), closes [TokenService#validate](https://github.com/TokenService/issues/validate) [#126](https://github.com/PIVOT-PLATFORM/pivot-core/issues/126)

# [0.14.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.13.0...v0.14.0) (2026-07-05)


### Features

* **api:** super admin crée un tenant (US06.2.1) ([#134](https://github.com/PIVOT-PLATFORM/pivot-core/issues/134)) ([7b06546](https://github.com/PIVOT-PLATFORM/pivot-core/commit/7b065460538b2158e4b271837c3121b6198a8afe)), closes [#135](https://github.com/PIVOT-PLATFORM/pivot-core/issues/135) [#126](https://github.com/PIVOT-PLATFORM/pivot-core/issues/126) [#135](https://github.com/PIVOT-PLATFORM/pivot-core/issues/135) [#126](https://github.com/PIVOT-PLATFORM/pivot-core/issues/126) [#128](https://github.com/PIVOT-PLATFORM/pivot-core/issues/128)

# [0.13.0](https://github.com/PIVOT-PLATFORM/pivot-core/compare/v0.12.0...v0.13.0) (2026-07-05)


### Features

* export de données personnelles RGPD Art. 20 (US02.3.1) ([#133](https://github.com/PIVOT-PLATFORM/pivot-core/issues/133)) ([c8d5f8c](https://github.com/PIVOT-PLATFORM/pivot-core/commit/c8d5f8c86dcb3966f5839b8d1241d807fb4cde1c)), closes [DataExportService#createPendingRequest](https://github.com/DataExportService/issues/createPendingRequest) [#131](https://github.com/PIVOT-PLATFORM/pivot-core/issues/131)

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
