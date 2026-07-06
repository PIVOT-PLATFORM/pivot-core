# Gestion des secrets — Docker secrets (EN07.2)

> **Portée :** secrets applicatifs statiques nécessaires au démarrage de `pivot-core` en
> production (mot de passe PostgreSQL, mot de passe SMTP, mot de passe Redis, clé HMAC OTP).
> Portée volontairement limitée — voir `ADR-014` (`pivot-docs/docs/adr/`) : cette approche est
> l'étape 1, remplacée à terme par un coffre-fort central (OpenBao, secrets dynamiques à courte
> durée de vie) sous **EN43.6**, hors scope ici. Les secrets OIDC par tenant
> (`tenant_oidc_configs.client_secret_enc`, stockés chiffrés en base) sont un mécanisme distinct,
> non couvert par ce document.

## Mécanisme

`pivot-core` ne contient **aucun code custom** de lecture de secrets : le mécanisme repose
entièrement sur les fonctionnalités natives de Spring Boot 4.x.

1. Le profil `prod` (`src/main/resources/application-prod.yml`) déclare :

   ```yaml
   spring:
     config:
       import: "optional:configtree:${SECRET_FILE_PATH:/run/secrets}/"
   ```

   `configtree:` transforme chaque fichier d'un répertoire en propriété Spring (nom de fichier
   → clé, contenu → valeur). `optional:` évite tout échec de démarrage si le répertoire est
   absent (ex. exécution du profil `prod` sans secrets montés). `SECRET_FILE_PATH` permet de
   surcharger le chemin (défaut : `/run/secrets`, point de montage standard des Docker
   secrets/Compose `secrets:`) — ne pas le faire terminer par `/` (déjà ajouté par le
   placeholder) : sans effet fonctionnel mais superflu.

2. Chaque secret est référencé dans `application.yml` (commun à tous les profils) via un
   placeholder à deux niveaux :

   ```yaml
   password: ${SPRING_DATASOURCE_PASSWORD:${secret.datasource-password:pivot}}
   ```

   Ordre de résolution : variable d'environnement classique (dev, `compose.yml`) **>** secret
   Docker (`prod`, via config tree) **>** défaut local codé en dur (dev uniquement, jamais
   utilisé en prod car aucune des deux premières sources n'y est absente). Note : le relaxed
   binding Spring Boot fait déjà gagner une variable d'environnement classique
   (`SPRING_DATASOURCE_PASSWORD`) sur une valeur en dur sans le moindre placeholder — le rôle
   du placeholder `${...:${...}}` ici n'est pas d'activer cette précédence (déjà acquise
   nativement), mais d'ouvrir explicitement le second niveau de repli vers le secret Docker.

3. **Pourquoi le namespace `secret.*`** plutôt que d'importer directement sous la clé Spring
   finale (ex. `spring.datasource.password`) : évite toute ambiguïté sur la précédence entre la
   valeur importée par le config tree et la valeur déjà déclarée dans `application.yml` — les
   deux ne partagent jamais la même clé, donc aucune dépendance à l'ordre d'évaluation des
   `PropertySource`.

## Secrets gérés

| Secret | Fichier Docker secret (nom exact) | Propriété Spring | Variable d'env (dev/compose) | Défaut dev |
|--------|-----------------------------------|-------------------|-------------------------------|------------|
| Mot de passe PostgreSQL | `secret.datasource-password` | `spring.datasource.password` | `SPRING_DATASOURCE_PASSWORD` / `POSTGRES_PASSWORD` | `pivot` |
| Mot de passe SMTP | `secret.mail-password` | `spring.mail.password` | `SPRING_MAIL_PASSWORD` / `MAIL_PASSWORD` | *(vide)* |
| Mot de passe Redis (si AUTH activé) | `secret.redis-password` | `spring.data.redis.password` | `SPRING_DATA_REDIS_PASSWORD` | *(vide, pas d'AUTH)* |
| Clé HMAC OTP (device / suppression compte) | `secret.auth-otp-secret` | `pivot.auth.otp-secret` | `PIVOT_AUTH_OTP_SECRET` | *(vide → clé éphémère, cf. `CryptoUtils.resolveOtpSecret`)* |

## Intégration `docker-compose.prod.yml` (EN07.1)

> **Point de réconciliation ouvert (2026-07-06) :** la version actuelle de `docker-compose.prod.yml`
> sur `feat/en07-1-docker-compose-prod` (PR #149) câble les `target:` directement sur les clés
> Spring finales (`SPRING_DATASOURCE_PASSWORD`, `SPRING_MAIL_PASSWORD`, `pivot.auth.otp-secret`)
> et déclare `SPRING_CONFIG_IMPORT` en variable d'environnement séparée, plutôt que le namespace
> `secret.*` décrit ci-dessous — PR #149 anticipe elle-même explicitement ce point ("expect to
> reconcile... whichever PR merges second"). À réconcilier avant que les deux PR soient mergées :
> voir le commentaire de coordination sur les PR #149/#150. Le contrat `secret.*` ci-dessous
> reste la cible car il élimine la dépendance à la précédence exacte entre le config tree
> importé et une clé homonyme déjà déclarée dans `application.yml` (cf. § Mécanisme, point 3) —
> une garantie que le ciblage direct des clés finales n'offre pas.

Contrat attendu par ce mécanisme — à câbler dans `docker-compose.prod.yml` (EN07.1) : chaque
secret est déclaré au niveau racine `secrets:`, adossé à un fichier externe (jamais une valeur
`environment:` en clair), puis monté sur le service `pivot-core` avec `target:` égal au nom de
la clé `secret.*` ci-dessus (le nom de fichier monté doit correspondre exactement, `configtree`
en dérive la clé de propriété) :

```yaml
secrets:
  datasource_password:
    file: ./secrets/datasource_password.txt        # ou `external: true` en Swarm
  mail_password:
    file: ./secrets/mail_password.txt
  redis_password:
    file: ./secrets/redis_password.txt
  auth_otp_secret:
    file: ./secrets/auth_otp_secret.txt

services:
  pivot-core:
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SECRET_FILE_PATH: /run/secrets
    secrets:
      - source: datasource_password
        target: secret.datasource-password
      - source: mail_password
        target: secret.mail-password
      - source: redis_password
        target: secret.redis-password
      - source: auth_otp_secret
        target: secret.auth-otp-secret
```

Aucune de ces valeurs n'apparaît jamais dans `docker-compose.prod.yml` en clair — seul le
chemin vers un fichier de secret externe au dépôt (`./secrets/*.txt`, exclu de Git, ou secret
Swarm/Kubernetes) y figure.

## Développement local

En dehors du profil `prod`, aucun secret Docker n'est requis : `.env` (copié depuis
`.env.example`, jamais commité) fournit les variables d'environnement classiques consommées par
`compose.yml`, avec des valeurs par défaut de confort (mot de passe PostgreSQL `pivot_dev`,
Mailpit sans authentification, etc.) — inadaptées et **non utilisées** en production, où le
profil `prod` + les Docker secrets prennent le relais.

## Procédure de rotation d'un secret

1. **Générer** la nouvelle valeur (ex. `openssl rand -base64 32` pour `auth_otp_secret`,
   nouveau mot de passe via l'outil d'administration PostgreSQL/SMTP/Redis concerné).
2. **Appliquer côté infrastructure en premier** — changer le mot de passe réel sur le service
   cible (PostgreSQL `ALTER ROLE ... PASSWORD`, SMTP, Redis `CONFIG SET requirepass` /
   configuration puis restart) **avant** de changer le fichier secret consommé par
   `pivot-core`, pour éviter une fenêtre où l'ancien secret est déjà invalide côté backend mais
   pas encore côté service.
   - **Exception `auth_otp_secret`** : purement interne à `pivot-core` (clé HMAC, aucun service
     tiers) — pas d'étape infra, seule la rotation applicative (étapes 3-4) s'applique.
     Conséquence de la rotation : tous les OTP device/suppression-compte déjà émis et non
     encore vérifiés deviennent invalides (même effet que l'absence de clé configurée —
     `CryptoUtils.resolveOtpSecret`) ; sans impact sur les sessions actives (mécanisme séparé).
3. **Écrire** la nouvelle valeur dans le fichier secret externe (`./secrets/*.txt` ou secret
   Swarm/Kubernetes versionné) — jamais dans Git, jamais en argument de ligne de commande
   (visible dans l'historique shell/process list).
4. **Recréer les conteneurs** consommant le secret (`docker compose -f docker-compose.prod.yml
   up -d --force-recreate pivot-core` ou équivalent Swarm) : Spring Boot lit le config tree au
   démarrage uniquement — **aucun rechargement à chaud**, un redémarrage du conteneur est requis
   pour prendre en compte la nouvelle valeur.
5. **Vérifier** `/actuator/health` (statut `UP`) puis un flux applicatif dépendant du secret
   changé (connexion BDD, envoi mail, cache Redis, ou émission/vérification d'un OTP) avant de
   considérer la rotation terminée.
6. **Révoquer** l'ancienne valeur côté service tiers dès la vérification concluante (mot de
   passe PostgreSQL/SMTP/Redis) — ne jamais laisser les deux valides simultanément au-delà de la
   fenêtre de vérification.

**Fréquence recommandée :** au minimum lors de tout départ d'un opérateur ayant eu accès aux
secrets, et en cas de suspicion de fuite — sinon selon la politique de rotation définie par le
mainteneur (aucune automatisation de rotation périodique tant qu'`EN43.6`/OpenBao n'est pas en
place).

## Non-buts (hors scope EN07.2)

- Rotation **automatique** ou secrets **dynamiques** à courte durée de vie — `EN43.6` (OpenBao).
- Chiffrement au repos des secrets OIDC par tenant (`tenant_oidc_configs.client_secret_enc`) —
  mécanisme distinct, non traité ici.
- Révocation en une action d'un secret fuité à l'échelle de plusieurs modules — `ADR-014`.
