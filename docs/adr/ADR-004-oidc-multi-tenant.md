# ADR-004 — Stratégie OIDC multi-tenant

**Statut :** Accepté  
**Date :** 2026-06-19  
**Décideurs :** Alexandre Solane

---

## Contexte

PIVOT doit supporter plusieurs modèles d'authentification :
- SaaS public : authentification locale JWT
- Enterprise : connecteur OIDC vers l'IdP du client (Keycloak, Azure AD, Okta…)
- Participants anonymes : accès via token de session (ROLE_GUEST)

## Décision

**Spring Security OAuth2 Resource Server** avec PKCE S256.

Le mapping claims → rôles est configurable par tenant :

| Rôle PIVOT | Claim OIDC source |
|------------|-------------------|
| `ROLE_SUPER_ADMIN` | Claim configurable par tenant |
| `ROLE_ADMIN` | Claim configurable par tenant |
| `ROLE_USER` | Claim par défaut (tout utilisateur authentifié) |
| `ROLE_GUEST` | Token de session court-vécu (sans OIDC) |

Le `TenantContext` est résolu depuis le JWT à chaque requête et propagé à toutes les couches (modules, services, repositories).

## Conséquences

**Positives :**
- Compatible tout IdP standard OIDC sans code spécifique
- PKCE S256 = protection contre l'interception du code d'autorisation
- Mapping configurable = clients enterprise peuvent mapper leurs propres claims

**Négatives / Contraintes :**
- La configuration du mapping claims par tenant doit être persistée et mise en cache (Redis)
- Toute modification de la chaîne Spring Security = **hard block Gate 4 + revue humaine**
- ROLE_GUEST nécessite un mécanisme de token court-vécu distinct du flux OIDC principal
