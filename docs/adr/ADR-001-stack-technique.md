# ADR-001 — Stack technique PIVOT

**Statut :** Accepté  
**Date :** 2026-06-19  
**Décideurs :** Alexandre Solane

---

## Contexte

Les outils collaboratifs du marché (Klaxoon, Miro, Kahoot…) sont des SaaS payants, fermés, sans possibilité d'auto-hébergement. PIVOT veut offrir une alternative open-source de qualité équivalente, accessible aux associations, TPE/PME et entreprises, sans dépendance à un éditeur tiers.

## Décision

| Couche | Choix | Raison |
|--------|-------|--------|
| Backend | Java 25 (LTS) + Spring Boot 4.1 + Maven | Écosystème enterprise mature, LTS, Spring Security / OIDC intégré, typage fort |
| Frontend | Angular 22 + TypeScript + SCSS | Framework opinioné adapté aux applications d'entreprise, OnPush, RxJS |
| BDD | PostgreSQL 18 | SGBD relationnel open-source, JSON natif, performances, fiabilité |
| Migrations | Flyway | Standard Java, SQL natif, pas d'ORM-migration magic |
| Cache / Temps réel | Redis + Spring WebSocket STOMP | Redis = sessions distribuées, STOMP = protocole messagerie standardisé |
| Auth | Spring Security + JWT + OIDC | Interopérabilité universelle (Keycloak, Azure AD, Okta), PKCE S256 |
| Tests backend | JUnit 5 + Mockito + Testcontainers | Pas de H2 — tests sur vraie base PostgreSQL |
| Tests frontend | Jest + Playwright | Jest = unitaires Angular, Playwright = E2E cross-browser |
| Conteneurisation | Docker + Docker Compose | Déploiement self-hosted simplifié |

## Conséquences

**Positives :**
- Stack connue et documentée, large base de contributeurs potentiels
- Spring Security gère nativement JWT, OIDC, CORS, CSRF
- Testcontainers garantit la fidélité des TI vs production

**Négatives / Contraintes :**
- Java plus verbeux que Node.js pour le bootstrapping initial
- Angular a une courbe d'apprentissage plus raide que React
- Nécessite JDK 25 + Node 24+ en développement local
- Spring Boot 4.x est un saut majeur (Spring Framework 7) — choisi sans coût de migration car projet greenfield ; moins de contenu communautaire que la branche 3.x à ce stade
