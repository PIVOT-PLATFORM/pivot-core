# Security Policy

## Versions supportées

| Version | Support |
|---------|---------|
| dernière release | ✅ Support complet |
| dernière - 1 | ✅ Correctifs sécurité uniquement |
| antérieures | ❌ Aucun support |

---

## Signaler une vulnérabilité

**Ne pas ouvrir d'issue publique GitHub pour une vulnérabilité de sécurité.**

Une divulgation publique avant qu'un correctif soit disponible expose tous les utilisateurs PIVOT.

### Comment signaler

Utiliser **GitHub Private Vulnerability Reporting** :

```
https://github.com/ApoSkunz/PIVOT/security/advisories/new
```

### Informations à inclure

- Composant affecté (`backend`, `frontend`, `auth`, module concerné)
- Version PIVOT et plateforme (OS, version Java, navigateur)
- Description de la vulnérabilité et impact potentiel
- Étapes de reproduction — aussi détaillées que possible
- Preuve de concept si disponible (optionnel mais apprécié)

---

## Délais de réponse

| Étape | Objectif |
|-------|----------|
| Accusé de réception | 48 heures |
| Évaluation initiale | 5 jours ouvrés |
| Correctif — Critique (CVSS ≥ 9.0) | 7 jours |
| Correctif — Élevé (CVSS 7.0–8.9) | 30 jours |
| Correctif — Moyen / Faible | Prochaine release planifiée |

---

## Politique de divulgation

PIVOT applique la **divulgation coordonnée** :

1. Le rapporteur soumet via advisory privé
2. Les mainteneurs accusent réception et évaluent la sévérité
3. Le correctif est développé sur une branche privée
4. Le correctif est releasé et taggé
5. L'advisory de sécurité est publié avec assignation CVE (si applicable)
6. Le rapporteur est crédité dans l'advisory (sauf anonymat demandé)

Pas de programme bug bounty actuellement.

---

## Périmètre

### Dans le périmètre

- **Backend** — API REST, Spring Security, filtres JWT, OIDC resource server, gestion des rôles
- **Frontend** — XSS, contournement d'authentification, exposition de données en navigateur
- **Auth** — OIDC PKCE S256, claims mapping, escalade de privilèges via rôles
- **Modules** — contournement de restriction admin, accès à module désactivé
- **WebSocket / STOMP** — injection via messages, autorisation insuffisante sur canaux
- **Docker Compose** — mauvaises valeurs par défaut en production, exposition de ports

### Hors périmètre

- Vulnérabilités dans les dépendances tierces (signaler au projet upstream — suivi via OWASP Dependency-Check)
- Problèmes nécessitant un accès physique à la machine hôte
- Attaques d'ingénierie sociale
- Déni de service par épuisement des ressources (limitation connue du self-hosting)
- Problèmes dans l'environnement de dev local (`docker-compose.yml` sans profil production)

---

## Principes de sécurité

PIVOT est conçu avec ces propriétés de sécurité :

- **Pas d'entités JPA exposées en API** — DTOs uniquement, enforced par code review
- **OIDC PKCE S256** — pas de client_secret côté navigateur
- **Rôles vérifiés côté API** — module désactivé = 403 + module Angular non chargé
- **Secrets via variables d'environnement** — jamais en dur dans le code (enforced par Gitleaks CI)
- **Logs structurés JSON** — toute action state-changing auditée (backend)
- **SBOM généré à chaque release** — disponible dans GitHub Releases
- **SLSA L2** — provenance attestée sur chaque release (JAR + image Docker)
- **Pas de `--no-verify`** — hooks qualité non contournables

---

## Scanning actif en CI

| Outil | Couverture |
|-------|-----------|
| Gitleaks | Secrets dans le code et l'historique git |
| CodeQL | SAST Java + JavaScript/TypeScript |
| Semgrep | OWASP Top 10, injection SQL, XSS, JWT, Spring |
| OWASP Dependency-Check | SCA — dépendances Maven + npm |
| ZAP DAST | Scan dynamique authentifié (ROLE_USER + ROLE_ADMIN) |
| Plumber | Conformité et hardening des workflows CI/CD |
| OpenSSF Scorecard | Score de sécurité open-source global |
