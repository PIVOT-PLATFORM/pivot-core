# Panneau Partage — noms des membres · Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Afficher pastille (avatar/initiales) + prénom nom + e-mail au lieu de l'identifiant numérique dans la table des membres du panneau de partage du whiteboard.

**Architecture:** Enrichissement local dans le module `collaboratif` de pivot-core — `UserDirectoryEntry` (vue lecture seule de `public.users`, déjà en place) gagne les champs d'identité, `MemberResponse` les expose, `BoardMemberService.listMembers()` charge l'annuaire en une requête groupée (pas de N+1). Le front collaboratif-ui reflète le DTO et rend une cellule pastille + 2 lignes avec repli image→initiales.

**Tech Stack:** Java 25 · Spring Boot 4 · JPA · Testcontainers (pivot-core) · Angular 22 · TypeScript strict · SCSS BEM · Transloco · Vitest (pivot-ui).

## Global Constraints

- **Deux repos = deux branches + deux PR.** Jamais de commit cross-repo. pivot-core d'abord (le DTO enrichi précède son usage front), pivot-ui ensuite.
- **pivot-core** : code d'abord puis tests **dans le même commit** ; coverage ≥ 85 % ; DTO jamais entité JPA exposée ; `mvn verify -q` = 0 erreur/0 warning avant push ; **aucune nouvelle migration Flyway** (les colonnes `first_name`/`last_name`/`avatar_url` existent déjà dans `public.users`).
- **pivot-ui** : TypeScript strict, **pas de `any`** ; OnPush ; signals ; SCSS BEM + tokens, pas de style inline ; i18n Transloco, **jamais de chaîne littérale** dans le template ; WCAG 2.1 AA ; `npx tsc --noEmit` + `npm run lint` + `npm run test:ci` + build prod = 0 erreur/warning avant push. **`tsc --noEmit` est un no-op ici (tsconfig racine = references) — vérifier avec `npx tsc -b` ou via le build.**
- **i18n en double** : toute clé whiteboard doit être modifiée **à la fois** dans `projects/collaboratif-ui/i18n/{fr,en}.json` ET `public/assets/i18n/{fr,en}.json` — ce dernier gagne le merge à l'exécution ; ne corriger que la lib laisse la clé non traduite à l'écran.
- **Sécurité — contrat acté** : `listMembers`/`invite` exposent désormais e-mail + nom aux membres du board. `requireAccess()` garantit déjà que l'appelant est membre. Pas d'élargissement de l'autorisation, seulement du payload. Ne PAS ajouter de `tenantId`/`userId` venant du body.
- **Repli obligatoire** (pas cosmétique) : `first_name`/`last_name` sont nullable → nom composé sinon e-mail sinon « Utilisateur inconnu » + `#id`. Avatar externe (IdP) bloqué par la CSP (`img-src 'self' data:`) → `<img (error)>` retombe sur les initiales. **La CSP n'est pas modifiée.**
- Convention maison : dupliquer une petite fonction pure plutôt que la partager entre features (cf. `profileInitials`, `passwordsMatch`) → le helper d'affichage vit dans collaboratif-ui, on n'importe rien du shell.

---

# PARTIE A — pivot-core (branche `feat/share-panel-member-names`, déjà créée)

> Repo : `/home/tellebma/DEV/edf/PIVOT-PLATFORM/pivot-core`. La branche existe déjà (le design doc y est committé). Vérifier une issue GitHub de suivi (`gh issue list --search "partage membre"`) ; absente → la créer et l'auto-assigner avant le 1ᵉʳ commit de code, la référencer dans la PR (`Closes #N`).

## Task A1 : Enrichir le DTO membre avec l'identité de l'utilisateur

Une seule unité revue : entité + repository + DTO + service + tests d'intégration, **un seul commit** (convention pivot-core : tests dans le même commit).

**Files:**
- Modify: `collaboratif/src/main/java/fr/pivot/collaboratif/whiteboard/member/UserDirectoryEntry.java`
- Modify: `collaboratif/src/main/java/fr/pivot/collaboratif/whiteboard/member/UserDirectoryRepository.java`
- Modify: `collaboratif/src/main/java/fr/pivot/collaboratif/whiteboard/member/dto/MemberResponse.java`
- Modify: `collaboratif/src/main/java/fr/pivot/collaboratif/whiteboard/member/BoardMemberService.java:80-91` (listMembers) et `:146-164` (chemins de retour d'invite)
- Modify (test): `collaboratif/src/test/java/fr/pivot/collaboratif/testsupport/PlatformAuthTestSupport.java` (mirror `public.users` + seed noms)
- Modify (test): `collaboratif/src/test/java/fr/pivot/collaboratif/whiteboard/member/BoardMemberControllerIT.java`

**Interfaces:**
- Produces (contrat REST consommé par la Partie B) : `GET /collaboratif/whiteboard/boards/{boardId}/members` et `POST .../members` renvoient désormais des objets
  `{ userId: number, email: string|null, firstName: string|null, lastName: string|null, avatarUrl: string|null, role: "OWNER"|"EDITOR"|"VIEWER", joinedAt: string }`.
  Les 4 champs d'identité valent `null` quand l'utilisateur est absent de l'annuaire (compte supprimé/désactivé/hors tenant).
- `PATCH .../members/{userId}/role` **inchangé** : renvoie toujours les champs d'identité à `null` (le front ne lit que `role` de cette réponse — cf. `share-panel.component.ts:147`). Ne pas enrichir updateRole (YAGNI).

- [ ] **Step 1 : Étendre `UserDirectoryEntry`** avec les 3 colonnes nullable (les colonnes existent dans `public.users`, aucune migration).

Remplacer le bloc des champs (après `active`, avant le constructeur) et ajouter les getters :

```java
    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "avatar_url")
    private String avatarUrl;
```

Ajouter après `isActive()` :

```java
    /**
     * Returns the user's first name.
     *
     * @return the {@code first_name}, or {@code null} if never set (local account, or OIDC
     *         provider that omitted the {@code given_name} claim)
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * Returns the user's last name.
     *
     * @return the {@code last_name}, or {@code null} if never set
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * Returns the user's avatar URL.
     *
     * @return the {@code avatar_url} (an IdP {@code picture} claim or a locally served
     *         {@code /api/avatars/...} path), or {@code null} if none
     */
    public String getAvatarUrl() {
        return avatarUrl;
    }
```

Mettre à jour la Javadoc de classe : la projection mappe désormais aussi `first_name`, `last_name`, `avatar_url` (affichage du nom du membre dans le panneau de partage, en plus de la résolution par e-mail).

- [ ] **Step 2 : Ajouter le finder groupé** dans `UserDirectoryRepository` (import `java.util.Collection`, `java.util.List`).

```java
    /**
     * Batch-loads directory entries by id within a tenant, for enriching a member list without an
     * N+1 query. A membership id absent from {@code public.users} (deleted/foreign-tenant account)
     * simply yields no row — the caller renders it as an unknown member.
     *
     * @param ids      the {@code public.users.id} values to resolve
     * @param tenantId the calling tenant's {@code public.tenants.id}
     * @return the matching entries (order unspecified; caller indexes by id)
     */
    List<UserDirectoryEntry> findAllByIdInAndTenantId(Collection<Long> ids, Long tenantId);
```

- [ ] **Step 3 : Étendre `MemberResponse`** — nouveau record + deux fabriques.

```java
package fr.pivot.collaboratif.whiteboard.member.dto;

import fr.pivot.collaboratif.whiteboard.board.BoardMember;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.member.UserDirectoryEntry;

import java.time.Instant;

/**
 * Read-only DTO representing a board member's identity, role, and join timestamp.
 *
 * <p>The identity fields ({@code email}, {@code firstName}, {@code lastName}, {@code avatarUrl})
 * are {@code null} when the member's {@code public.users} row cannot be resolved — a deleted,
 * deactivated, or foreign-tenant account. The frontend renders such a row as an unknown member
 * showing {@code #<userId>}. Exposed only to callers already granted board access
 * (see {@code BoardMemberService.requireAccess}).
 *
 * @param userId    the member's {@code public.users.id}
 * @param email     the member's e-mail, or {@code null} if the account is unresolved
 * @param firstName the member's first name, or {@code null}
 * @param lastName  the member's last name, or {@code null}
 * @param avatarUrl the member's avatar URL, or {@code null}
 * @param role      the member's current role on the board
 * @param joinedAt  the instant the user joined the board
 */
public record MemberResponse(
        Long userId,
        String email,
        String firstName,
        String lastName,
        String avatarUrl,
        BoardRole role,
        Instant joinedAt) {

    /**
     * Builds a response with no resolved identity (identity fields {@code null}). Used when the
     * directory entry is unavailable, and for endpoints whose callers do not consume identity
     * (role update).
     *
     * @param member the board member entity
     * @return the response record with null identity fields
     */
    public static MemberResponse from(final BoardMember member) {
        return from(member, null);
    }

    /**
     * Builds a response enriched with directory identity.
     *
     * @param member the board member entity
     * @param entry  the resolved directory entry, or {@code null} if the user is unknown
     * @return the response record
     */
    public static MemberResponse from(final BoardMember member, final UserDirectoryEntry entry) {
        return new MemberResponse(
                member.getId().getUserId(),
                entry != null ? entry.getEmail() : null,
                entry != null ? entry.getFirstName() : null,
                entry != null ? entry.getLastName() : null,
                entry != null ? entry.getAvatarUrl() : null,
                member.getRole(),
                member.getJoinedAt());
    }
}
```

- [ ] **Step 4 : Enrichir `listMembers`** (`BoardMemberService.java:80-91`). Ajouter imports `java.util.Map`, `java.util.function.Function`, `java.util.stream.Collectors`.

```java
    public List<MemberResponse> listMembers(
            final UUID boardId,
            final Long callerId,
            final Long tenantId) {
        Board board = boardRepository.findByIdAndTenantId(boardId, tenantId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
        requireAccess(boardId, callerId, board.getOwnerId());

        List<BoardMember> members =
                boardMemberRepository.findAllByIdBoardIdOrderByJoinedAtAsc(boardId);
        List<Long> userIds = members.stream()
                .map(m -> m.getId().getUserId())
                .toList();
        Map<Long, UserDirectoryEntry> directory = userIds.isEmpty()
                ? Map.of()
                : userDirectoryRepository.findAllByIdInAndTenantId(userIds, tenantId).stream()
                        .collect(Collectors.toMap(UserDirectoryEntry::getId, Function.identity()));

        return members.stream()
                .map(m -> MemberResponse.from(m, directory.get(m.getId().getUserId())))
                .toList();
    }
```

- [ ] **Step 5 : Enrichir les retours d'`invite`** (`BoardMemberService.java`). L'`invitee` (`UserDirectoryEntry`) est déjà résolu en local — le passer aux 3 chemins de retour. Remplacer :
  - ligne ~153 : `return MemberResponse.from(created);` → `return MemberResponse.from(created, invitee);`
  - ligne ~162 : `return MemberResponse.from(saved);` → `return MemberResponse.from(saved, invitee);`
  - ligne ~164 : `return MemberResponse.from(existing);` → `return MemberResponse.from(existing, invitee);`

  (Laisser `updateRole` `:208` inchangé — `from(saved)` sans entry.)

- [ ] **Step 6 : Patcher le mirror de schéma de test.** `PlatformAuthTestSupport.createPublicSchema` (`:74-83`) recrée `public.users` sans les nouvelles colonnes → la requête JPA échouerait. Ajouter les 3 colonnes au `CREATE TABLE`, après `is_active` :

```java
                    CREATE TABLE IF NOT EXISTS public.users (
                        id BIGSERIAL PRIMARY KEY,
                        tenant_id BIGINT NOT NULL REFERENCES public.tenants(id),
                        email VARCHAR(320) NOT NULL,
                        role VARCHAR(50) NOT NULL DEFAULT 'ROLE_USER',
                        is_active BOOLEAN NOT NULL DEFAULT true,
                        first_name VARCHAR(100),
                        last_name VARCHAR(100),
                        avatar_url TEXT,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
```

- [ ] **Step 7 : Ajouter un seed avec noms.** Dans `PlatformAuthTestSupport`, à côté de `seedUserWithEmail` (`:206`), ajouter une surcharge posant prénom/nom (repartir de la même signature JDBC ; adapter le `RETURNING id`) :

```java
    /**
     * Inserts an active user with an e-mail and a display name, returning its generated id.
     *
     * @param jdbcUrl   the JDBC URL
     * @param username  the database username
     * @param password  the database password
     * @param tenantId  the owning tenant's id
     * @param email     the user's e-mail
     * @param firstName the user's first name (nullable)
     * @param lastName  the user's last name (nullable)
     * @return the generated {@code public.users.id}
     * @throws SQLException if the insert fails
     */
    public static long seedUserWithName(
            final String jdbcUrl, final String username, final String password,
            final long tenantId, final String email,
            final String firstName, final String lastName) throws SQLException {
        final String sql = "INSERT INTO public.users "
                + "(tenant_id, email, role, is_active, first_name, last_name) "
                + "VALUES (?, ?, 'ROLE_USER', true, ?, ?) RETURNING id";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, email);
            ps.setString(3, firstName);
            ps.setString(4, lastName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
```

- [ ] **Step 8 : Étendre le seed de l'éditeur dans l'IT** pour qu'il porte un nom, et ajouter les assertions. Dans `BoardMemberControllerIT.setUp()` (`:90-93`), remplacer l'appel `seedUserWithEmail(...)` par `seedUserWithName(..., "Marie", "Dupont")` (garder `editorEmail`). Puis enrichir `listMembers_asOwner_returnsBothMembers` (`:121`) :

```java
    @Test
    void listMembers_asOwner_returnsBothMembers() throws Exception {
        mockMvc.perform(get(BOARDS_PATH + "/" + boardId + "/members")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].role").value("OWNER"))
                .andExpect(jsonPath("$[1].userId").value(editorId))
                .andExpect(jsonPath("$[1].role").value("EDITOR"))
                .andExpect(jsonPath("$[1].email").value(editorEmail))
                .andExpect(jsonPath("$[1].firstName").value("Marie"))
                .andExpect(jsonPath("$[1].lastName").value("Dupont"));
    }
```

- [ ] **Step 9 : Ajouter un test « membre inconnu »** (identité nulle). Nouveau `@Test` : insérer une adhésion pour un `userId` inexistant dans `public.users`, lister, vérifier identité nulle et rôle présent.

```java
    @Test
    void listMembers_memberAbsentFromDirectory_returnsNullIdentity() throws Exception {
        long ghostId = 999_000_000L; // aucun public.users correspondant
        boardMemberRepository.save(new BoardMember(
                new BoardMemberId(boardId, ghostId), BoardRole.VIEWER, Instant.now()));

        mockMvc.perform(get(BOARDS_PATH + "/" + boardId + "/members")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[?(@.userId == " + ghostId + ")].email").value(hasSize(1)))
                .andExpect(jsonPath("$[?(@.userId == " + ghostId + ")].email[0]").doesNotExist())
                .andExpect(jsonPath("$[?(@.userId == " + ghostId + ")].role[0]").value("VIEWER"));
    }
```

> Si l'assertion JSONPath sur un champ null est capricieuse (Jackson sérialise `null`), replier sur : désérialiser le body en `List<Map<String,Object>>` avec `mapper` et asserter `entry.get("email") == null` — le fixture `mapper` est déjà présent (`:65`). Choisir la forme qui passe ; l'objectif testé = identité nulle + rôle présent pour un membre orphelin.

- [ ] **Step 10 : Vérifier l'invite enrichie** — étendre une assertion d'un test d'invite existant (`invite_ownerInvitesNewUser_returns201WithDefaultViewerRole`, `:287`) pour confirmer que la réponse porte l'e-mail (et le nom si le seed de l'invité en a un). Ajouter :

```java
                .andExpect(jsonPath("$.email").value(inviteeEmail));
```
(adapter au nom de variable e-mail de l'invité dans ce test ; si l'invité n'a pas de nom seedé, n'asserter que l'e-mail.)

- [ ] **Step 11 : `mvn verify`**

Run: `cd /home/tellebma/DEV/edf/PIVOT-PLATFORM/pivot-core && mvn -q -pl collaboratif -am verify`
Expected: BUILD SUCCESS, 0 warning Checkstyle/SpotBugs, tous les IT membres verts.

- [ ] **Step 12 : Mettre à jour le backlog puis committer** (fichier par fichier, jamais `git add .`).

```bash
git add collaboratif/src/main/java/fr/pivot/collaboratif/whiteboard/member/UserDirectoryEntry.java \
        collaboratif/src/main/java/fr/pivot/collaboratif/whiteboard/member/UserDirectoryRepository.java \
        collaboratif/src/main/java/fr/pivot/collaboratif/whiteboard/member/dto/MemberResponse.java \
        collaboratif/src/main/java/fr/pivot/collaboratif/whiteboard/member/BoardMemberService.java \
        collaboratif/src/test/java/fr/pivot/collaboratif/testsupport/PlatformAuthTestSupport.java \
        collaboratif/src/test/java/fr/pivot/collaboratif/whiteboard/member/BoardMemberControllerIT.java
git commit -m "feat(api): expose le nom et l'avatar des membres dans MemberResponse

La table des membres du panneau de partage n'avait que l'id numérique à
afficher. listMembers et invite enrichissent désormais chaque membre depuis
public.users (chargement groupé, sans N+1) ; identité nulle si le compte est
absent de l'annuaire. Exposé aux seuls membres du board (requireAccess).

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019Swn73KGuDKyp9jcwowaxE"
```

- [ ] **Step 13 : PR + autoloop** vers `main` (draft), `Closes #N`, label `feat`. Merge selon Gate 4. **Ne pas** poser le trailer `Release-Trigger` (hors fin de sprint).

---

# PARTIE B — pivot-ui (nouvelle branche, APRÈS merge de la Partie A)

> Repo : `/home/tellebma/DEV/edf/PIVOT-PLATFORM/pivot-ui`. Créer la branche depuis `main` à jour :
> ```bash
> cd /home/tellebma/DEV/edf/PIVOT-PLATFORM/pivot-ui && git checkout main && git pull origin main && git checkout -b feat/share-panel-member-names
> ```
> Vérifier/créer l'issue de suivi comme en Partie A.

## Task B1 : Modèle + helper d'affichage (pur, testé)

**Files:**
- Modify: `projects/collaboratif-ui/src/lib/core/whiteboard/board.model.ts:12-16`
- Create: `projects/collaboratif-ui/src/lib/whiteboard/share-panel/member-display.ts`
- Create: `projects/collaboratif-ui/src/lib/whiteboard/share-panel/member-display.spec.ts`

**Interfaces:**
- Consumes : le contrat REST enrichi de la Partie A.
- Produces : `memberDisplayName(m: BoardMember): string | null`, `memberInitials(m: BoardMember): string`, utilisés par Task B2.

- [ ] **Step 1 : Étendre `BoardMember`** (`board.model.ts:12-16`).

```ts
export interface BoardMember {
  userId: number;
  /** Identity fields are `null` when the account is unresolved (deleted / foreign-tenant). */
  email: string | null;
  firstName: string | null;
  lastName: string | null;
  avatarUrl: string | null;
  role: 'OWNER' | 'EDITOR' | 'VIEWER';
  joinedAt: string;
}
```
Mettre à jour la Javadoc du bloc pour mentionner l'identité et le cas non résolu.

- [ ] **Step 2 : Écrire les tests du helper** (`member-display.spec.ts`). Convention repo : mêmes fichiers, un commit ; on écrit test+code ensemble.

```ts
import { describe, expect, it } from 'vitest';
import { memberDisplayName, memberInitials } from './member-display';
import type { BoardMember } from '../../core/whiteboard/board.model';

function member(over: Partial<BoardMember>): BoardMember {
  return { userId: 1, email: null, firstName: null, lastName: null,
    avatarUrl: null, role: 'EDITOR', joinedAt: '2026-01-01', ...over };
}

describe('memberDisplayName', () => {
  it('compose prénom + nom quand les deux sont présents', () => {
    expect(memberDisplayName(member({ firstName: 'Marie', lastName: 'Dupont' }))).toBe('Marie Dupont');
  });
  it('accepte le prénom seul', () => {
    expect(memberDisplayName(member({ firstName: 'Marie' }))).toBe('Marie');
  });
  it('accepte le nom seul', () => {
    expect(memberDisplayName(member({ lastName: 'Dupont' }))).toBe('Dupont');
  });
  it('retombe sur l’e-mail sans nom', () => {
    expect(memberDisplayName(member({ email: 'm@edf.fr' }))).toBe('m@edf.fr');
  });
  it('retourne null si ni nom ni e-mail (membre inconnu)', () => {
    expect(memberDisplayName(member({}))).toBeNull();
  });
});

describe('memberInitials', () => {
  it('prend les initiales du prénom et du nom', () => {
    expect(memberInitials(member({ firstName: 'Marie', lastName: 'Dupont' }))).toBe('MD');
  });
  it('retombe sur la 1re lettre de l’e-mail', () => {
    expect(memberInitials(member({ email: 'zoe@edf.fr' }))).toBe('Z');
  });
  it('retourne "?" pour un membre inconnu', () => {
    expect(memberInitials(member({}))).toBe('?');
  });
});
```

- [ ] **Step 3 : Lancer les tests → échec attendu** (module absent).

Run: `cd /home/tellebma/DEV/edf/PIVOT-PLATFORM/pivot-ui && npx vitest run projects/collaboratif-ui/src/lib/whiteboard/share-panel/member-display.spec.ts`
Expected: FAIL — `Cannot find module './member-display'`.

- [ ] **Step 4 : Écrire le helper** (`member-display.ts`).

```ts
import type { BoardMember } from '../../core/whiteboard/board.model';

/**
 * Human-readable name for a board member: composed first/last name, else e-mail, else `null`
 * when the account is unresolved (the template then shows an "unknown member" label + #id).
 * Pure — duplicated here rather than shared with the shell's `profileInitials`, per this
 * codebase's convention for tiny pure functions.
 */
export function memberDisplayName(member: BoardMember): string | null {
  const full = [member.firstName, member.lastName].filter(Boolean).join(' ').trim();
  return full || member.email || null;
}

/**
 * Up to two uppercase initials for the avatar fallback: first/last initials, else the e-mail's
 * first letter, else `'?'` for an unresolved account.
 */
export function memberInitials(member: BoardMember): string {
  const first = member.firstName?.[0] ?? '';
  const last = member.lastName?.[0] ?? '';
  return `${first}${last}`.toUpperCase() || member.email?.[0]?.toUpperCase() || '?';
}
```

- [ ] **Step 5 : Lancer les tests → succès.**

Run: `npx vitest run projects/collaboratif-ui/src/lib/whiteboard/share-panel/member-display.spec.ts`
Expected: PASS (8 tests).

- [ ] **Step 6 : Commit.**

```bash
git add projects/collaboratif-ui/src/lib/core/whiteboard/board.model.ts \
        projects/collaboratif-ui/src/lib/whiteboard/share-panel/member-display.ts \
        projects/collaboratif-ui/src/lib/whiteboard/share-panel/member-display.spec.ts
git commit -m "feat(ui): modèle membre enrichi + helper d'affichage du nom

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019Swn73KGuDKyp9jcwowaxE"
```

## Task B2 : Cellule pastille + nom + e-mail, i18n, composant

**Files:**
- Modify: `projects/collaboratif-ui/src/lib/whiteboard/share-panel/share-panel.component.ts` (retirer `SlicePipe` inutilisé, exposer les helpers, ajouter `onAvatarError`)
- Modify: `projects/collaboratif-ui/src/lib/whiteboard/share-panel/share-panel.component.html:172` et `:183-185`
- Modify: `projects/collaboratif-ui/src/lib/whiteboard/share-panel/share-panel.component.scss`
- Modify: `projects/collaboratif-ui/src/lib/whiteboard/share-panel/share-panel.component.spec.ts` (fixtures BoardMember avec nouveaux champs + rendu du nom)
- Modify: `projects/collaboratif-ui/i18n/fr.json:170`, `projects/collaboratif-ui/i18n/en.json:170`
- Modify: `public/assets/i18n/fr.json:739`, `public/assets/i18n/en.json:739`

- [ ] **Step 1 : i18n — renommer + ajouter la clé.** Dans les **4** fichiers, remplacer la ligne `memberUserId` et ajouter `memberUnknown` juste après :
  - `projects/collaboratif-ui/i18n/fr.json:170` et `public/assets/i18n/fr.json:739` :
    ```json
        "memberLabel": "Membre",
        "memberUnknown": "Utilisateur inconnu",
    ```
  - `projects/collaboratif-ui/i18n/en.json:170` et `public/assets/i18n/en.json:739` :
    ```json
        "memberLabel": "Member",
        "memberUnknown": "Unknown user",
    ```
  (Supprimer l'ancienne clé `memberUserId` dans les 4.)

- [ ] **Step 2 : Composant TS** — retirer l'import `SlicePipe` (déjà inutilisé), l'ôter du tableau `imports`, exposer les helpers et gérer l'erreur d'image.

Dans `share-panel.component.ts` : ligne 11 `import { DatePipe, SlicePipe } from '@angular/common';` → `import { DatePipe } from '@angular/common';` ; ligne 33 `imports: [TranslocoPipe, DatePipe, SlicePipe],` → `imports: [TranslocoPipe, DatePipe],`. Ajouter l'import du helper en tête :
```ts
import { memberDisplayName, memberInitials } from './member-display';
```
Ajouter dans la classe (après les signals), un set des avatars en échec + les ponts vers les helpers :
```ts
  /** userIds whose avatar <img> failed to load — fall back to initials. */
  protected readonly avatarFailed = signal<ReadonlySet<number>>(new Set());

  protected displayName(member: BoardMember): string | null {
    return memberDisplayName(member);
  }

  protected initials(member: BoardMember): string {
    return memberInitials(member);
  }

  protected showAvatar(member: BoardMember): boolean {
    return !!member.avatarUrl && !this.avatarFailed().has(member.userId);
  }

  protected onAvatarError(member: BoardMember): void {
    this.avatarFailed.update(set => new Set(set).add(member.userId));
  }
```

- [ ] **Step 3 : En-tête de colonne** (`share-panel.component.html:172`) :
```html
            <th scope="col" class="share-panel__th">{{ 'whiteboard.share.panel.memberLabel' | transloco }}</th>
```

- [ ] **Step 4 : Cellule membre** — remplacer `share-panel.component.html:183-185` par la pastille + 2 lignes. `title` conserve l'id pour le support ; repli image→initiales ; libellé inconnu traduit.

```html
              <td class="share-panel__td share-panel__td--member" [attr.title]="'#' + member.userId">
                <div class="share-panel__member">
                  <span class="share-panel__avatar" aria-hidden="true">
                    @if (showAvatar(member)) {
                      <img
                        class="share-panel__avatar-img"
                        [src]="member.avatarUrl"
                        alt=""
                        (error)="onAvatarError(member)"
                      />
                    } @else {
                      {{ initials(member) }}
                    }
                  </span>
                  <span class="share-panel__member-text">
                    @if (displayName(member); as name) {
                      <span class="share-panel__member-name">{{ name }}</span>
                      @if (member.email && (member.firstName || member.lastName)) {
                        <span class="share-panel__member-email">{{ member.email }}</span>
                      }
                    } @else {
                      <span class="share-panel__member-name share-panel__member-name--unknown">
                        {{ 'whiteboard.share.panel.memberUnknown' | transloco }}
                      </span>
                      <span class="share-panel__member-email">#{{ member.userId }}</span>
                    }
                  </span>
                </div>
              </td>
```

- [ ] **Step 5 : SCSS** — ajouter au `share-panel.component.scss` (tokens existants du fichier ; adapter les noms de variables à ceux déjà utilisés dans le fichier — vérifier l'en-tête du fichier pour les tokens couleur/espacement).

```scss
.share-panel__member {
  display: flex;
  align-items: center;
  gap: 0.625rem;
}

.share-panel__avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 2rem;
  height: 2rem;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--color-surface-alt, #e2e8f0);
  color: var(--color-text-muted, #475569);
  font-size: 0.75rem;
  font-weight: 600;
  overflow: hidden;
  text-transform: uppercase;
}

.share-panel__avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.share-panel__member-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.share-panel__member-name {
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.share-panel__member-name--unknown {
  font-style: italic;
  color: var(--color-text-muted, #64748b);
}

.share-panel__member-email {
  font-size: 0.8125rem;
  color: var(--color-text-muted, #64748b);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
```
Retirer/renommer l'ancienne règle `.share-panel__td--id` si elle devient inutilisée.

- [ ] **Step 6 : Mettre à jour le spec du composant.** Dans `share-panel.component.spec.ts`, tout fixture `BoardMember` doit porter les nouveaux champs (`email`, `firstName`, `lastName`, `avatarUrl`) sinon TS strict casse. Ajouter au moins un cas : membres seedés avec `firstName/lastName` → le DOM rend le nom composé, pas `member.userId`. Ajouter un cas identité nulle → rend la clé `memberUnknown` + `#id`. (Reprendre les helpers de fixture existants du fichier ; ne pas dupliquer la logique testée en B1.)

- [ ] **Step 7 : Vérifications pré-push** (les 4, 0 erreur/0 warning).

```bash
cd /home/tellebma/DEV/edf/PIVOT-PLATFORM/pivot-ui
npx tsc -b                                        # (tsc --noEmit est un no-op ici)
npm run lint
npm run test:ci
npm run build -- --configuration production
```
Expected: tout vert.

- [ ] **Step 8 : Commit** (fichier par fichier).

```bash
git add projects/collaboratif-ui/src/lib/whiteboard/share-panel/share-panel.component.ts \
        projects/collaboratif-ui/src/lib/whiteboard/share-panel/share-panel.component.html \
        projects/collaboratif-ui/src/lib/whiteboard/share-panel/share-panel.component.scss \
        projects/collaboratif-ui/src/lib/whiteboard/share-panel/share-panel.component.spec.ts \
        projects/collaboratif-ui/i18n/fr.json projects/collaboratif-ui/i18n/en.json \
        public/assets/i18n/fr.json public/assets/i18n/en.json
git commit -m "feat(ui): affiche nom/avatar des membres dans le panneau de partage

Remplace l'id numérique par une pastille (avatar ou initiales, repli si
l'image échoue) + nom composé + e-mail. Membre non résolu → « Utilisateur
inconnu » + #id conservé en title. Clé i18n memberUserId → memberLabel.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019Swn73KGuDKyp9jcwowaxE"
```

## Task B3 (différable) : Recette Playwright

- [ ] Après merge, `pivot-core/dev-refresh.sh` puis recette manuelle Playwright : ouvrir un board, panneau Partage, vérifier nom+e-mail affichés (pas l'id), inviter un membre → nom rendu immédiatement, screenshot. Conforme à la règle « recette Playwright systématique après dev-refresh ».

---

## Séquencement récapitulatif

1. **A** (pivot-core) : Task A1 → PR → merge.
2. **B** (pivot-ui) : `git pull main`, Task B1 → B2 → PR → merge.
3. `dev-refresh.sh` + recette (B3).
