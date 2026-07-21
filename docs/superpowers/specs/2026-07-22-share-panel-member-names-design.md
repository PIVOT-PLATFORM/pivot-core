# Panneau Partage — afficher prénom/nom au lieu de l'identifiant

**Date** : 2026-07-22
**Repos concernés** : `pivot-core` (module `collaboratif`), `pivot-ui` (`projects/collaboratif-ui`)
**Problème** : la table des membres du panneau de partage du whiteboard affiche
`member.userId` (l'identifiant numérique interne) parce que c'est le seul champ que
l'API renvoie. On veut afficher l'identité humaine du membre.

## Contexte technique (état actuel)

- **DTO backend** : `MemberResponse(Long userId, BoardRole role, Instant joinedAt)`
  (`pivot-core/collaboratif/.../whiteboard/member/dto/MemberResponse.java`), construit
  depuis la seule entité `BoardMember` (aucune jointure vers `users`).
- **Modèle front** : `BoardMember { userId: number; role; joinedAt }`
  (`pivot-ui/projects/collaboratif-ui/src/lib/core/whiteboard/board.model.ts`).
- **Template** : `share-panel.component.html` affiche `{{ member.userId }}` (colonne
  « Identifiant » / clé i18n `whiteboard.share.panel.memberUserId`).
- **Annuaire déjà présent** : `BoardMemberService` injecte `UserDirectoryRepository`
  (lecture seule sur `public.users`) pour résoudre les invitations par e-mail —
  mais la projection `UserDirectoryEntry` n'expose que `id, tenantId, email, active`.
- **Noms disponibles en base** : `public.users.first_name` / `last_name` (nullable,
  alimentés au login OIDC via les claims `given_name`/`family_name`) et `avatar_url`
  (claim `picture`, ou upload local servi sous `/api/avatars/...`).

## Décisions de conception

### Rendu de la cellule
Pastille (avatar ou initiales) + nom sur la 1ʳᵉ ligne + e-mail grisé en sous-ligne.

```
┌─────────────────────────────┬──────────┐
│ Membre                      │ Rôle     │
├─────────────────────────────┼──────────┤
│ (MD)  Marie Dupont          │ Éditeur  │
│       marie.dupont@edf.fr   │          │
├─────────────────────────────┼──────────┤
│ ( ? )  Utilisateur inconnu  │ Lecteur  │
│        #4127                │          │
└─────────────────────────────┴──────────┘
```

### Pastille
`<img [src]="avatarUrl">` quand `avatarUrl` non nul, avec un handler `(error)` qui
bascule sur les initiales. Ce repli couvre **deux** cas : les URLs IdP externes
bloquées par la CSP (`img-src 'self' data:`, `nginx.conf:64`) et les images 404.
Les initiales réutilisent `profileInitials()` déjà écrit/testé, fond coloré dérivé
du `userId` (stable entre affichages). **La CSP n'est pas modifiée.**

### Cas dégradé (utilisateur introuvable dans l'annuaire)
Compte supprimé, désactivé, ou hors tenant → le membre reste visible (l'accès existe
toujours en base, on doit pouvoir le révoquer). Libellé traduit « Utilisateur inconnu »
+ `#<id>` en sous-ligne à la place de l'e-mail, pastille « ? ». Signal technique :
les champs nom/email du DTO valent `null`.

### Approche backend retenue : enrichissement local (option A)
On enrichit `UserDirectoryEntry` + chargement groupé dans `listMembers()`. Pas de
nouvelle API, pas d'aller-retour front supplémentaire, pas de migration Flyway
(colonnes déjà présentes). Écarté : endpoint de résolution partagé (option B — surface
API + double aller-retour, à faire le jour où un 2ᵉ module le réclame) et association
JPA `BoardMember→User` (option C — couple deux schémas, N+1, contraire au modulith).

### Sécurité — élargissement de contrat acté
Le DTO expose désormais e-mail + nom des membres à tout appelant ayant accès au board.
`requireAccess()` garantit déjà que l'appelant est membre. Divulgation d'identité
limitée aux personnes partageant déjà le tableau → validé.

## Changements

### 1. Backend — `pivot-core`, module `collaboratif` (branche + PR d'abord)
- `UserDirectoryEntry` : + `firstName`, `lastName`, `avatarUrl` (nullable, colonnes
  `first_name`/`last_name`/`avatar_url` existantes).
- `UserDirectoryRepository` : + `List<UserDirectoryEntry> findAllByIdInAndTenantId(Collection<Long>, Long)`.
- `MemberResponse` : `(Long userId, String email, String firstName, String lastName,
  String avatarUrl, BoardRole role, Instant joinedAt)`. Les 4 champs identité valent
  `null` si l'utilisateur est absent de l'annuaire.
- `BoardMemberService.listMembers()` : après chargement des membres, un seul appel
  annuaire sur l'ensemble des `userId` → `Map<Long, UserDirectoryEntry>` → mapping.
  Deux requêtes au total, pas de N+1.
- Tests : `BoardMemberControllerIT` étendu (nouveaux champs présents ; cas utilisateur
  absent de l'annuaire → champs identité nuls).

### 2. Front — `pivot-ui/projects/collaboratif-ui` (branche + PR après merge core)
- `board.model.ts` : `BoardMember` reflète le nouveau DTO (champs identité `string | null`).
- Fonction pure `memberDisplayName(member)` à côté du modèle : nom composé, sinon
  e-mail, sinon `null` (→ « Utilisateur inconnu »). Testable isolément.
- `share-panel.component.html` : pastille + 2 lignes ; `<img (error)>` → initiales ;
  `title` sur la ligne conserve l'`#id` pour le support.
- SCSS : pastille ronde, sous-ligne e-mail grisée.
- Tests : specs sur `memberDisplayName` (4 cas) + repli image.

### 3. i18n
- `whiteboard.share.panel.memberUserId` → `memberLabel` (« Membre » / « Member »).
- + `memberUnknown` (« Utilisateur inconnu » / « Unknown user »).
- À poser dans `projects/collaboratif-ui/i18n/{fr,en}.json` **ET**
  `pivot-ui/public/assets/i18n` (ce dernier duplique les clés whiteboard et gagne le
  merge — ne corriger que la lib laisserait la clé non traduite à l'écran).

## Séquencement
1. `pivot-core` : branche `feat/share-panel-member-names`, PR, merge (backend fait foi).
2. `pivot-ui` : branche, PR après que le DTO enrichi est disponible.
3. `dev-refresh.sh` + recette Playwright sur le panneau de partage.

Règle d'isolation : deux repos = deux branches + deux PR, jamais un commit cross-repo.
