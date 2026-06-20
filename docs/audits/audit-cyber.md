# Audit Sécurité Applicative

## Périmètre

OWASP Top 10, JWT, OIDC, CORS, CSRF, injection, XSS, IDOR, secrets.

## Critères évalués

| Critère | Statut | Notes |
|---------|--------|-------|
| Pas de secrets dans le code | ✅ OK | Tout en variables d'environnement |
| Gitleaks configuré | ✅ OK | `.github/workflows/configuration/.gitleaks.toml` |
| CodeQL actif (Java + JS) | ✅ OK | `security.yml` |
| Semgrep actif (OWASP + JWT + SQLi + XSS) | ✅ OK | `security.yml` |
| DAST ZAP Full Scan (ROLE_USER + ROLE_ADMIN) | ✅ OK | `dast-full.yml` sur push main |
| DAST ZAP Baseline (prod) | ✅ OK | `dast-baseline.yml` mensuel |
| SCA OWASP Dependency Check (CVSS ≥ 7) | ✅ OK | `ci.yml` job sca |
| npm audit (prod deps, high+) | ✅ OK | `ci.yml` job sca |
| CORS explicite | ⬜ À implémenter | À configurer dans `SecurityConfig` |
| CSP headers | ⬜ À implémenter | — |
| JWT validation robuste | ⬜ À implémenter | — |
| OIDC PKCE S256 | ⬜ À implémenter | — |
| `@PreAuthorize` sur endpoints sensibles | ⬜ À implémenter | — |
| Protection IDOR (scope tenant sur toutes les requêtes) | ⬜ À implémenter | — |
| Audit log structuré JSON | ⬜ À implémenter | — |

## Vecteurs Red Team prioritaires (à challenger à chaque US auth/module)

1. JWT forgé sans signature valide
2. OIDC claim injection (rôle elevé dans le token)
3. IDOR cross-tenant (accès aux données d'un autre tenant)
4. Module désactivé accessible via route directe
5. Secrets exposés dans les logs ou les réponses API

## Historique des révisions

| Version | Date | Score | Évolutions principales |
|---------|------|-------|------------------------|
| v1 | 2026-06-20 | 3/10 | Audit initial — pipeline sécurité posé, pas d'implémentation applicative |
