# Traçabilité AC → tests — US #73 (Authentification PIVOT SaaS)

Chaque critère d'acceptation est mappé à au moins un test. AC sans test = non implémenté.

| Critère d'acceptation | Test(s) |
|-----------------------|---------|
| OIDC : token à mauvaise audience (`aud`) → 401 | `OidcAuthServiceTest#oidcValidator_rejectsToken_withWrongAudience` ; cas positif `#oidcValidator_acceptsToken_withCorrectAudience` |
| OIDC : IdP désactivé (`is_active=false`) → 403 | `OidcAuthServiceTest#exchange_throws403_whenOidcConfigInactive` |
| OIDC : `tid` Azure non concordant → refus | couvert par le validateur (`OidcAuthService#oidcValidator`, claim `tid`) — même mécanisme que l'audience |
| JIT : `default_role` non whitelisté (ex. ROLE_SUPER_ADMIN) → ROLE_USER | `OidcAuthServiceTest#sanitizeProvisionedRole_rejectsPlatformAndUnknownRoles` |
| Login : email inconnu → réponse neutre + temps égalisé | `SessionServiceTest` (login bad credentials, BCrypt leurre via `runDecoyPasswordCheck`) |
| Register : email existant → réponse neutre (pas de 409) + email | `RegistrationServiceTest#register_isNeutral_whenEmailAlreadyExists` |
| Rotation : fenêtre de grâce, pas de 401 concurrent | `TokenServiceTest#rotate_returnsNewToken_whenTokenStillActive`, `#rotate_returnsEmpty_whenAlreadyRotated` ; TI `TokenServiceIntegrationTest#rotate_keepsOldInGraceAndIssuesNew_inDb` |
| Rotation : pas d'éviction d'une autre session à MAX_SESSIONS | `TokenServiceTest#rotate_doesNotEnforceMaxSessions` ; TI `#rotate_atMaxSessions_doesNotEvictHealthySession` |
| OTP appareil : plafond de tentatives → 429 | `SessionServiceTest` (verifyDeviceOtp : rate-limit + garde dure `attempts`) |
| Sécurité : X-Forwarded-For honoré seulement derrière proxy de confiance | `AuthControllerTest#register_ignoresXForwardedFor_usesRemoteAddr`, `GoogleAuthControllerTest#authenticate_ignoresXForwardedFor_usesRemoteAddr` ; config `RemoteIpValve` (application.yml) |
| Sécurité : OTP haché en HMAC-SHA256 | `CryptoUtilsTest` (hmacSha256) + `SessionServiceTest` (verifyDeviceOtp avec HMAC) |
| last_used_at écrit en asynchrone (pas d'écriture sur le chemin requête) | `TokenServiceTest#validate_dispatchesAsyncLastUsedTouch_whenValid` ; TI `#validate_returnsToken_andUpdatesLastUsedAt_asynchronously` |

Validation locale (reviewer) : `mvn verify -Pcoverage` → BUILD SUCCESS, 176 tests, couverture JaCoCo conforme. CI : 14 checks verts.
