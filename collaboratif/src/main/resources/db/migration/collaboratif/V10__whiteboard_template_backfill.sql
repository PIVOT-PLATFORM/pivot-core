-- V10: idempotent backfill of V7's whiteboard-template payload, for databases that recorded V7 as
-- applied without ever actually running its body (issue #248).
--
-- Convention note (see CLAUDE.md, "Migrations Flyway — fichier V1 unique avant la BETA", and the
-- headers of V2..V9 for the precedent this follows): V1..V9 have already been applied against the
-- real, persistent recette-managed Cloud SQL instance by the continuous-deploy pipeline — Flyway
-- has recorded their checksums, so editing any of them in place would break validation on the next
-- deploy. V7 therefore cannot be edited in place, and V10 replays its content additively and
-- idempotently instead.
--
-- On the maintainer sign-off the convention requires: the fix itself was approved on issue #248,
-- and the choice of a numbered file over editing V1/V7 was confirmed separately when this PR was
-- merged. The alternative was never actually open — editing an already-applied migration is the
-- exact defect this file repairs.
--
-- Why this migration exists: V7__whiteboard_template_replatform.sql was edited *after* it had
-- already been applied against `pivot_dev` (and possibly recette) — its `ALTER TABLE ... ADD
-- COLUMN local_key`, the DELETE+re-author of the 3 original templates' elements, and the 7 new
-- global templates (with their elements) were all added to the file post-application. Flyway keys
-- a migration by version, not content: it never re-runs an already-recorded V7, and the checksum
-- drift this produces is silently absorbed by `flyway repair()` in the module runner (see
-- `ModuleFlywayConfigurer`'s Javadoc and V8's header for the same pattern with `collaboratif.frame`)
-- — the recorded checksum is realigned to the edited file WITHOUT the edited body ever executing.
--
-- Investigating this against the local `pivot_dev` database (V7 recorded `success=t`) showed the
-- affected scope is *larger* than "7 missing global templates": `local_key` does not exist on
-- `whiteboard_template_element` at all, and the 3 original templates (BRAINSTORM, RETROSPECTIVE,
-- USER_STORY_MAP) still carry their legacy `SHAPE`/`TEXT`/`IMAGE` elements instead of V7's
-- re-authored `FRAME`/`CARD`/`CONNECTION`/`FIELD`/`FIELD_VALUE` ones. Since `TemplateElementType`
-- (the enum `WhiteboardTemplateElement#elementType` is read into) no longer declares
-- `SHAPE`/`TEXT`/`IMAGE` at all, initializing a board from any of these 3 templates on such a
-- database does not just render invisible content — it throws (`IllegalArgumentException`: no
-- enum constant) the moment `WhiteboardTemplateService#initializeBoard` reads that element back.
-- V10 therefore backfills the *entire* V7 delta, not only the 7 new templates: the schema change,
-- the 3 re-authored templates' elements, and the 7 new global templates with their elements.
--
-- Idempotence strategy: `whiteboard_template` has a fixed `id` primary key, so its header INSERT
-- reuses V7's own `ON CONFLICT (id) DO NOTHING` verbatim. `whiteboard_template_element` has no
-- natural unique key beyond its server-generated `id` (see V1__schema_init.sql) — a plain INSERT
-- would duplicate rows on every replay. Two distinct guards are used instead, chosen per situation:
--   * For the 7 new templates (4444...-aaaa...): these ids never existed before V7, so their
--     elements are inserted only `WHERE NOT EXISTS` any row for that `template_id` yet — a
--     database that already has them (fresh install, or a previous run of this very migration)
--     is left untouched.
--   * For the 3 original templates (1111.../2222.../3333...): they may currently hold either the
--     pre-V7 legacy rows (SHAPE/TEXT/IMAGE) or the already-re-authored V7 rows (FRAME/CARD/...).
--     The legacy rows are deleted only if at least one legacy-typed row is still present for that
--     template (`EXISTS ... element_type IN ('SHAPE','TEXT','IMAGE')`), and the re-authored rows
--     are (re-)inserted only `WHERE NOT EXISTS` any new-vocabulary row for that template yet. On a
--     database where V7 already ran in full, neither the DELETE nor the INSERT does anything; on a
--     database like the affected `pivot_dev`, the DELETE clears the 5/7/6 legacy rows once and the
--     INSERT seeds the re-authored ones once. Re-running this file a second time is a full no-op
--     either way — verified locally by executing it twice against `pivot_dev` and diffing element
--     counts per template before/after.
--
-- Content: every payload below is copied verbatim from V7 — this migration re-plays, it does not
-- re-author.

ALTER TABLE collaboratif.whiteboard_template_element
    ADD COLUMN IF NOT EXISTS local_key VARCHAR(64);

-- ============================================================================================
-- BRAINSTORM (re-authored) — backfill guard: purge legacy elements only if still present, then
-- (re-)insert the re-authored set only if it isn't there yet.
-- ============================================================================================
DELETE FROM collaboratif.whiteboard_template_element
WHERE template_id = '11111111-1111-1111-1111-111111111111'
  AND EXISTS (
      SELECT 1 FROM collaboratif.whiteboard_template_element e2
      WHERE e2.template_id = '11111111-1111-1111-1111-111111111111'
        AND e2.element_type IN ('SHAPE', 'TEXT', 'IMAGE')
  );

INSERT INTO collaboratif.whiteboard_template_element
    (template_id, element_type, local_key, payload, display_order)
SELECT v.template_id, v.element_type, v.local_key, v.payload, v.display_order
FROM (VALUES
    ('11111111-1111-1111-1111-111111111111'::uuid, 'CARD'::varchar, NULL::varchar,
     '{"type":"LABEL","content":"Brainstorming","posX":40,"posY":20,"width":400,"height":50}'::jsonb, 0),
    ('11111111-1111-1111-1111-111111111111', 'CARD', NULL,
     '{"type":"LABEL","content":"Règles : viser la quantité, aucune idée n''est jugée, rebondir sur celles des autres.","posX":40,"posY":80,"width":700,"height":40,"color":"#F1F5F9"}',
     1),
    ('11111111-1111-1111-1111-111111111111', 'FRAME', NULL,
     '{"title":"Idées","posX":40,"posY":140,"width":500,"height":400,"color":"#FEF9C3"}', 2),
    ('11111111-1111-1111-1111-111111111111', 'CARD', NULL,
     '{"type":"TEXT","content":"Idée 1","posX":60,"posY":180,"width":200,"height":120,"color":"#FEF08A"}',
     3),
    ('11111111-1111-1111-1111-111111111111', 'CARD', NULL,
     '{"type":"TEXT","content":"Idée 2","posX":280,"posY":180,"width":200,"height":120,"color":"#FEF08A"}',
     4),
    ('11111111-1111-1111-1111-111111111111', 'FRAME', NULL,
     '{"title":"Regrouper","posX":560,"posY":140,"width":300,"height":400,"color":"#DBEAFE"}', 5),
    ('11111111-1111-1111-1111-111111111111', 'FRAME', NULL,
     '{"title":"Top idées","posX":880,"posY":140,"width":300,"height":400,"color":"#DCFCE7"}', 6)
) AS v(template_id, element_type, local_key, payload, display_order)
WHERE NOT EXISTS (
    SELECT 1 FROM collaboratif.whiteboard_template_element e
    WHERE e.template_id = '11111111-1111-1111-1111-111111111111'::uuid
      AND e.element_type IN ('FRAME', 'CARD', 'CONNECTION', 'FIELD', 'FIELD_VALUE')
);

-- ============================================================================================
-- RETROSPECTIVE (re-authored, enriched) — same guard pattern as BRAINSTORM above.
-- ============================================================================================
DELETE FROM collaboratif.whiteboard_template_element
WHERE template_id = '22222222-2222-2222-2222-222222222222'
  AND EXISTS (
      SELECT 1 FROM collaboratif.whiteboard_template_element e2
      WHERE e2.template_id = '22222222-2222-2222-2222-222222222222'
        AND e2.element_type IN ('SHAPE', 'TEXT', 'IMAGE')
  );

INSERT INTO collaboratif.whiteboard_template_element
    (template_id, element_type, local_key, payload, display_order)
SELECT v.template_id, v.element_type, v.local_key, v.payload, v.display_order
FROM (VALUES
    ('22222222-2222-2222-2222-222222222222'::uuid, 'CARD'::varchar, NULL::varchar,
     '{"type":"LABEL","content":"Rétrospective","posX":40,"posY":20,"width":600,"height":50}'::jsonb, 0),
    ('22222222-2222-2222-2222-222222222222', 'FRAME', NULL,
     '{"title":"Bien 🙂","posX":40,"posY":100,"width":300,"height":420,"color":"#DCFCE7"}', 1),
    ('22222222-2222-2222-2222-222222222222', 'FRAME', NULL,
     '{"title":"À améliorer 😕","posX":360,"posY":100,"width":300,"height":420,"color":"#FEE2E2"}', 2),
    ('22222222-2222-2222-2222-222222222222', 'FRAME', NULL,
     '{"title":"Actions ✅","posX":680,"posY":100,"width":300,"height":420,"color":"#DBEAFE"}', 3),
    ('22222222-2222-2222-2222-222222222222', 'CARD', NULL,
     '{"type":"TEXT","content":"Exemple : bonne collaboration","posX":60,"posY":150,"width":260,"height":80,"color":"#BBF7D0"}',
     4),
    ('22222222-2222-2222-2222-222222222222', 'CARD', NULL,
     '{"type":"TEXT","content":"Exemple : réunions trop longues","posX":380,"posY":150,"width":260,"height":80,"color":"#FECACA"}',
     5),
    ('22222222-2222-2222-2222-222222222222', 'CARD', 'actionCard',
     '{"type":"TEXT","content":"Exemple : fixer une durée max aux réunions","posX":700,"posY":150,"width":260,"height":100,"color":"#BFDBFE"}',
     6),
    ('22222222-2222-2222-2222-222222222222', 'FIELD', 'responsableField',
     '{"name":"Responsable","type":"TEXT","order":0}', 7),
    ('22222222-2222-2222-2222-222222222222', 'FIELD', 'echeanceField',
     '{"name":"Échéance","type":"DATE","order":1}', 8),
    ('22222222-2222-2222-2222-222222222222', 'FIELD_VALUE', NULL,
     '{"cardKey":"actionCard","fieldKey":"responsableField","value":"À définir"}', 9)
) AS v(template_id, element_type, local_key, payload, display_order)
WHERE NOT EXISTS (
    SELECT 1 FROM collaboratif.whiteboard_template_element e
    WHERE e.template_id = '22222222-2222-2222-2222-222222222222'::uuid
      AND e.element_type IN ('FRAME', 'CARD', 'CONNECTION', 'FIELD', 'FIELD_VALUE')
);

-- ============================================================================================
-- USER_STORY_MAP (re-authored) — same guard pattern as BRAINSTORM above.
-- ============================================================================================
DELETE FROM collaboratif.whiteboard_template_element
WHERE template_id = '33333333-3333-3333-3333-333333333333'
  AND EXISTS (
      SELECT 1 FROM collaboratif.whiteboard_template_element e2
      WHERE e2.template_id = '33333333-3333-3333-3333-333333333333'
        AND e2.element_type IN ('SHAPE', 'TEXT', 'IMAGE')
  );

INSERT INTO collaboratif.whiteboard_template_element
    (template_id, element_type, local_key, payload, display_order)
SELECT v.template_id, v.element_type, v.local_key, v.payload, v.display_order
FROM (VALUES
    ('33333333-3333-3333-3333-333333333333'::uuid, 'CARD'::varchar, NULL::varchar,
     '{"type":"LABEL","content":"User Story Map","posX":40,"posY":20,"width":600,"height":50}'::jsonb, 0),
    ('33333333-3333-3333-3333-333333333333', 'CARD', NULL,
     '{"type":"LABEL","content":"Légende : rangée du haut = parcours utilisateur (epics), rangée du bas = releases.","posX":40,"posY":80,"width":700,"height":40,"color":"#F1F5F9"}',
     1),
    ('33333333-3333-3333-3333-333333333333', 'FRAME', NULL,
     '{"title":"Epic 1","posX":40,"posY":140,"width":300,"height":120,"color":"#E0E7FF"}', 2),
    ('33333333-3333-3333-3333-333333333333', 'FRAME', NULL,
     '{"title":"Epic 2","posX":360,"posY":140,"width":300,"height":120,"color":"#E0E7FF"}', 3),
    ('33333333-3333-3333-3333-333333333333', 'FRAME', NULL,
     '{"title":"Release 1","posX":40,"posY":280,"width":620,"height":140,"color":"#F1F5F9"}', 4),
    ('33333333-3333-3333-3333-333333333333', 'CARD', NULL,
     '{"type":"TEXT","content":"User story","posX":60,"posY":320,"width":180,"height":80}', 5)
) AS v(template_id, element_type, local_key, payload, display_order)
WHERE NOT EXISTS (
    SELECT 1 FROM collaboratif.whiteboard_template_element e
    WHERE e.template_id = '33333333-3333-3333-3333-333333333333'::uuid
      AND e.element_type IN ('FRAME', 'CARD', 'CONNECTION', 'FIELD', 'FIELD_VALUE')
);

-- ============================================================================================
-- New global templates — headers. Fixed ids -> ON CONFLICT (id) DO NOTHING is a sufficient
-- guard (copied verbatim from V7).
-- ============================================================================================
INSERT INTO collaboratif.whiteboard_template
    (id, tenant_id, code, name, description, thumbnail_url, display_order)
VALUES
    ('44444444-4444-4444-4444-444444444444', NULL, 'RETRO_START_STOP_CONTINUE',
     'Rétrospective — Start / Stop / Continue',
     'Colonnes Commencer, Arrêter, Continuer pour une rétrospective d''équipe.',
     '/assets/templates/retro-start-stop-continue.svg', 3),
    ('55555555-5555-5555-5555-555555555555', NULL, 'RETRO_MAD_SAD_GLAD',
     'Rétrospective — Mad / Sad / Glad',
     'Colonnes Fâché, Triste, Content pour explorer le ressenti de l''équipe.',
     '/assets/templates/retro-mad-sad-glad.svg', 4),
    ('66666666-6666-6666-6666-666666666666', NULL, 'RETRO_4L',
     'Rétrospective — 4L',
     'Colonnes Liked, Learned, Lacked, Longed for.',
     '/assets/templates/retro-4l.svg', 5),
    ('77777777-7777-7777-7777-777777777777', NULL, 'RETRO_SPEEDBOAT',
     'Rétrospective — Bateau (Speedboat)',
     'Métaphore du voilier : vent, ancre, rochers, île, pour visualiser accélérateurs et freins.',
     '/assets/templates/retro-speedboat.svg', 6),
    ('88888888-8888-8888-8888-888888888888', NULL, 'RISK_ANALYSIS',
     'Analyse de risques',
     'Matrice probabilité x impact avec déroulé voter les types, lister, voter les risques, placer.',
     '/assets/templates/risk-analysis.svg', 7),
    ('99999999-9999-9999-9999-999999999999', NULL, 'MINDMAP',
     'Carte mentale',
     'Nœud central et branches reliées par connecteurs pour organiser des idées.',
     '/assets/templates/mindmap.svg', 8),
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', NULL, 'VISUAL_MANAGEMENT',
     'Management visuel',
     'Obeya d''équipe : météo, indicateurs, obstacles, actions, et bande Kanban.',
     '/assets/templates/visual-management.svg', 9)
ON CONFLICT (id) DO NOTHING;

-- ============================================================================================
-- RETRO_START_STOP_CONTINUE elements — brand-new template id, so a plain "no elements yet"
-- guard is sufficient (no legacy rows can exist for an id V7 introduced).
-- ============================================================================================
INSERT INTO collaboratif.whiteboard_template_element
    (template_id, element_type, local_key, payload, display_order)
SELECT v.template_id, v.element_type, v.local_key, v.payload, v.display_order
FROM (VALUES
    ('44444444-4444-4444-4444-444444444444'::uuid, 'CARD'::varchar, NULL::varchar,
     '{"type":"LABEL","content":"Start / Stop / Continue","posX":40,"posY":20,"width":600,"height":50}'::jsonb, 0),
    ('44444444-4444-4444-4444-444444444444', 'FRAME', NULL,
     '{"title":"Commencer ▶️","posX":40,"posY":100,"width":300,"height":420,"color":"#DBEAFE"}', 1),
    ('44444444-4444-4444-4444-444444444444', 'FRAME', NULL,
     '{"title":"Arrêter ⏹️","posX":360,"posY":100,"width":300,"height":420,"color":"#FEE2E2"}', 2),
    ('44444444-4444-4444-4444-444444444444', 'FRAME', NULL,
     '{"title":"Continuer 🔁","posX":680,"posY":100,"width":300,"height":420,"color":"#DCFCE7"}', 3)
) AS v(template_id, element_type, local_key, payload, display_order)
WHERE NOT EXISTS (
    SELECT 1 FROM collaboratif.whiteboard_template_element e
    WHERE e.template_id = '44444444-4444-4444-4444-444444444444'::uuid
);

-- ============================================================================================
-- RETRO_MAD_SAD_GLAD elements.
-- ============================================================================================
INSERT INTO collaboratif.whiteboard_template_element
    (template_id, element_type, local_key, payload, display_order)
SELECT v.template_id, v.element_type, v.local_key, v.payload, v.display_order
FROM (VALUES
    ('55555555-5555-5555-5555-555555555555'::uuid, 'CARD'::varchar, NULL::varchar,
     '{"type":"LABEL","content":"Mad / Sad / Glad","posX":40,"posY":20,"width":600,"height":50}'::jsonb, 0),
    ('55555555-5555-5555-5555-555555555555', 'FRAME', NULL,
     '{"title":"Fâché 😠","posX":40,"posY":100,"width":300,"height":420,"color":"#FECACA"}', 1),
    ('55555555-5555-5555-5555-555555555555', 'FRAME', NULL,
     '{"title":"Triste 😢","posX":360,"posY":100,"width":300,"height":420,"color":"#BFDBFE"}', 2),
    ('55555555-5555-5555-5555-555555555555', 'FRAME', NULL,
     '{"title":"Content 😄","posX":680,"posY":100,"width":300,"height":420,"color":"#FDE68A"}', 3)
) AS v(template_id, element_type, local_key, payload, display_order)
WHERE NOT EXISTS (
    SELECT 1 FROM collaboratif.whiteboard_template_element e
    WHERE e.template_id = '55555555-5555-5555-5555-555555555555'::uuid
);

-- ============================================================================================
-- RETRO_4L elements.
-- ============================================================================================
INSERT INTO collaboratif.whiteboard_template_element
    (template_id, element_type, local_key, payload, display_order)
SELECT v.template_id, v.element_type, v.local_key, v.payload, v.display_order
FROM (VALUES
    ('66666666-6666-6666-6666-666666666666'::uuid, 'CARD'::varchar, NULL::varchar,
     '{"type":"LABEL","content":"4L : Liked / Learned / Lacked / Longed for","posX":40,"posY":20,"width":700,"height":50}'::jsonb,
     0),
    ('66666666-6666-6666-6666-666666666666', 'FRAME', NULL,
     '{"title":"Liked 👍","posX":40,"posY":100,"width":240,"height":420,"color":"#DCFCE7"}', 1),
    ('66666666-6666-6666-6666-666666666666', 'FRAME', NULL,
     '{"title":"Learned 💡","posX":300,"posY":100,"width":240,"height":420,"color":"#FEF9C3"}', 2),
    ('66666666-6666-6666-6666-666666666666', 'FRAME', NULL,
     '{"title":"Lacked 👎","posX":560,"posY":100,"width":240,"height":420,"color":"#FEE2E2"}', 3),
    ('66666666-6666-6666-6666-666666666666', 'FRAME', NULL,
     '{"title":"Longed for 🌟","posX":820,"posY":100,"width":240,"height":420,"color":"#E0E7FF"}', 4)
) AS v(template_id, element_type, local_key, payload, display_order)
WHERE NOT EXISTS (
    SELECT 1 FROM collaboratif.whiteboard_template_element e
    WHERE e.template_id = '66666666-6666-6666-6666-666666666666'::uuid
);

-- ============================================================================================
-- RETRO_SPEEDBOAT elements.
-- ============================================================================================
INSERT INTO collaboratif.whiteboard_template_element
    (template_id, element_type, local_key, payload, display_order)
SELECT v.template_id, v.element_type, v.local_key, v.payload, v.display_order
FROM (VALUES
    ('77777777-7777-7777-7777-777777777777'::uuid, 'CARD'::varchar, NULL::varchar,
     '{"type":"LABEL","content":"Speedboat / Voilier","posX":40,"posY":20,"width":600,"height":50}'::jsonb, 0),
    ('77777777-7777-7777-7777-777777777777', 'CARD', NULL,
     '{"type":"LABEL","content":"Le bateau avance vers son objectif (l''île), poussé par le vent, freiné par l''ancre, menacé par les rochers.","posX":40,"posY":80,"width":900,"height":50,"color":"#F1F5F9"}',
     1),
    ('77777777-7777-7777-7777-777777777777', 'FRAME', NULL,
     '{"title":"🌬️ Vent (accélérateurs)","posX":40,"posY":160,"width":280,"height":300,"color":"#DBEAFE"}', 2),
    ('77777777-7777-7777-7777-777777777777', 'FRAME', NULL,
     '{"title":"⚓ Ancre (freins)","posX":340,"posY":160,"width":280,"height":300,"color":"#FEE2E2"}', 3),
    ('77777777-7777-7777-7777-777777777777', 'FRAME', NULL,
     '{"title":"🪨 Rochers (risques)","posX":640,"posY":160,"width":280,"height":300,"color":"#FECACA"}', 4),
    ('77777777-7777-7777-7777-777777777777', 'FRAME', NULL,
     '{"title":"🏝️ Île (objectif)","posX":940,"posY":160,"width":280,"height":300,"color":"#DCFCE7"}', 5)
) AS v(template_id, element_type, local_key, payload, display_order)
WHERE NOT EXISTS (
    SELECT 1 FROM collaboratif.whiteboard_template_element e
    WHERE e.template_id = '77777777-7777-7777-7777-777777777777'::uuid
);

-- ============================================================================================
-- RISK_ANALYSIS elements — voting staging (types, then raw ideas) + a 2x2 probability x impact
-- matrix (4 frames) + axis labels + board fields (type/probabilité/impact) for placement.
-- ============================================================================================
INSERT INTO collaboratif.whiteboard_template_element
    (template_id, element_type, local_key, payload, display_order)
SELECT v.template_id, v.element_type, v.local_key, v.payload, v.display_order
FROM (VALUES
    ('88888888-8888-8888-8888-888888888888'::uuid, 'CARD'::varchar, NULL::varchar,
     '{"type":"LABEL","content":"Analyse de risques","posX":40,"posY":20,"width":600,"height":50}'::jsonb, 0),
    ('88888888-8888-8888-8888-888888888888', 'CARD', NULL,
     '{"type":"LABEL","content":"Déroulé : ① voter les types de risques les plus probables → ② lister tous les risques → ③ voter les risques → ④ placer chaque risque dans la matrice.","posX":40,"posY":80,"width":1000,"height":60,"color":"#F1F5F9"}',
     1),
    ('88888888-8888-8888-8888-888888888888', 'FRAME', NULL,
     '{"title":"Types de risques (à voter)","posX":40,"posY":160,"width":260,"height":340,"color":"#EDE9FE"}',
     2),
    ('88888888-8888-8888-8888-888888888888', 'CARD', NULL,
     '{"type":"TEXT","content":"Technique","posX":60,"posY":200,"width":220,"height":50,"color":"#DDD6FE"}', 3),
    ('88888888-8888-8888-8888-888888888888', 'CARD', NULL,
     '{"type":"TEXT","content":"Planning","posX":60,"posY":260,"width":220,"height":50,"color":"#DDD6FE"}', 4),
    ('88888888-8888-8888-8888-888888888888', 'CARD', NULL,
     '{"type":"TEXT","content":"Budget","posX":60,"posY":320,"width":220,"height":50,"color":"#DDD6FE"}', 5),
    ('88888888-8888-8888-8888-888888888888', 'CARD', NULL,
     '{"type":"TEXT","content":"Ressource","posX":60,"posY":380,"width":220,"height":50,"color":"#DDD6FE"}', 6),
    ('88888888-8888-8888-8888-888888888888', 'CARD', NULL,
     '{"type":"TEXT","content":"Externe","posX":60,"posY":440,"width":220,"height":50,"color":"#DDD6FE"}', 7),
    ('88888888-8888-8888-8888-888888888888', 'FRAME', NULL,
     '{"title":"Risques bruts (idéation)","posX":340,"posY":160,"width":260,"height":340,"color":"#FEF9C3"}',
     8),
    ('88888888-8888-8888-8888-888888888888', 'FRAME', NULL,
     '{"title":"Faible impact / Faible probabilité","posX":660,"posY":160,"width":300,"height":300,"color":"#DCFCE7"}',
     9),
    ('88888888-8888-8888-8888-888888888888', 'FRAME', NULL,
     '{"title":"Fort impact / Faible probabilité","posX":980,"posY":160,"width":300,"height":300,"color":"#FEF08A"}',
     10),
    ('88888888-8888-8888-8888-888888888888', 'FRAME', NULL,
     '{"title":"Faible impact / Forte probabilité","posX":660,"posY":480,"width":300,"height":300,"color":"#FED7AA"}',
     11),
    ('88888888-8888-8888-8888-888888888888', 'FRAME', NULL,
     '{"title":"Fort impact / Forte probabilité","posX":980,"posY":480,"width":300,"height":300,"color":"#FECACA"}',
     12),
    ('88888888-8888-8888-8888-888888888888', 'CARD', NULL,
     '{"type":"LABEL","content":"Probabilité →","posX":660,"posY":790,"width":620,"height":30,"color":"#F1F5F9"}',
     13),
    ('88888888-8888-8888-8888-888888888888', 'CARD', NULL,
     '{"type":"LABEL","content":"↑ Impact","posX":600,"posY":160,"width":50,"height":300,"color":"#F1F5F9"}',
     14),
    ('88888888-8888-8888-8888-888888888888', 'FIELD', NULL,
     '{"name":"Type de risque","type":"SELECT","options":["Technique","Planning","Budget","Ressource","Externe","Qualité"],"order":0}',
     15),
    ('88888888-8888-8888-8888-888888888888', 'FIELD', NULL,
     '{"name":"Probabilité","type":"NUMBER","order":1}', 16),
    ('88888888-8888-8888-8888-888888888888', 'FIELD', NULL,
     '{"name":"Impact","type":"NUMBER","order":2}', 17)
) AS v(template_id, element_type, local_key, payload, display_order)
WHERE NOT EXISTS (
    SELECT 1 FROM collaboratif.whiteboard_template_element e
    WHERE e.template_id = '88888888-8888-8888-8888-888888888888'::uuid
);

-- ============================================================================================
-- MINDMAP elements — a central node, 4 primary branches, 2 secondary leaves, connected by
-- curved connectors (dashed for the secondary leaves to visually mark depth).
-- ============================================================================================
INSERT INTO collaboratif.whiteboard_template_element
    (template_id, element_type, local_key, payload, display_order)
SELECT v.template_id, v.element_type, v.local_key, v.payload, v.display_order
FROM (VALUES
    ('99999999-9999-9999-9999-999999999999'::uuid, 'CARD'::varchar, 'central'::varchar,
     '{"type":"LABEL","content":"Sujet central","posX":500,"posY":300,"width":200,"height":80,"color":"#C7D2FE"}'::jsonb,
     0),
    ('99999999-9999-9999-9999-999999999999', 'CARD', 'b1',
     '{"type":"TEXT","content":"Branche 1","posX":200,"posY":150,"width":160,"height":70,"color":"#FEF08A"}', 1),
    ('99999999-9999-9999-9999-999999999999', 'CARD', 'b2',
     '{"type":"TEXT","content":"Branche 2","posX":800,"posY":150,"width":160,"height":70,"color":"#FEF08A"}', 2),
    ('99999999-9999-9999-9999-999999999999', 'CARD', 'b3',
     '{"type":"TEXT","content":"Branche 3","posX":200,"posY":450,"width":160,"height":70,"color":"#FEF08A"}', 3),
    ('99999999-9999-9999-9999-999999999999', 'CARD', 'b4',
     '{"type":"TEXT","content":"Branche 4","posX":800,"posY":450,"width":160,"height":70,"color":"#FEF08A"}', 4),
    ('99999999-9999-9999-9999-999999999999', 'CARD', 'b1a',
     '{"type":"TEXT","content":"Sous-idée 1.1","posX":40,"posY":80,"width":140,"height":60,"color":"#BBF7D0"}', 5),
    ('99999999-9999-9999-9999-999999999999', 'CARD', 'b4a',
     '{"type":"TEXT","content":"Sous-idée 4.1","posX":900,"posY":600,"width":140,"height":60,"color":"#BBF7D0"}', 6),
    ('99999999-9999-9999-9999-999999999999', 'CONNECTION', NULL,
     '{"fromKey":"central","toKey":"b1","shape":"curved","lineStyle":"solid","startCap":"none","endCap":"arrow"}',
     7),
    ('99999999-9999-9999-9999-999999999999', 'CONNECTION', NULL,
     '{"fromKey":"central","toKey":"b2","shape":"curved","lineStyle":"solid","startCap":"none","endCap":"arrow"}',
     8),
    ('99999999-9999-9999-9999-999999999999', 'CONNECTION', NULL,
     '{"fromKey":"central","toKey":"b3","shape":"curved","lineStyle":"solid","startCap":"none","endCap":"arrow"}',
     9),
    ('99999999-9999-9999-9999-999999999999', 'CONNECTION', NULL,
     '{"fromKey":"central","toKey":"b4","shape":"curved","lineStyle":"solid","startCap":"none","endCap":"arrow"}',
     10),
    ('99999999-9999-9999-9999-999999999999', 'CONNECTION', NULL,
     '{"fromKey":"b1","toKey":"b1a","shape":"curved","lineStyle":"dashed","startCap":"none","endCap":"arrow"}',
     11),
    ('99999999-9999-9999-9999-999999999999', 'CONNECTION', NULL,
     '{"fromKey":"b4","toKey":"b4a","shape":"curved","lineStyle":"dashed","startCap":"none","endCap":"arrow"}',
     12)
) AS v(template_id, element_type, local_key, payload, display_order)
WHERE NOT EXISTS (
    SELECT 1 FROM collaboratif.whiteboard_template_element e
    WHERE e.template_id = '99999999-9999-9999-9999-999999999999'::uuid
);

-- ============================================================================================
-- VISUAL_MANAGEMENT elements — an Obeya-style status row (météo/indicateurs/obstacles/actions)
-- plus a 4-column Kanban band, and board fields to track card status/owner/due date.
-- ============================================================================================
INSERT INTO collaboratif.whiteboard_template_element
    (template_id, element_type, local_key, payload, display_order)
SELECT v.template_id, v.element_type, v.local_key, v.payload, v.display_order
FROM (VALUES
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid, 'CARD'::varchar, NULL::varchar,
     '{"type":"LABEL","content":"Management visuel (Obeya / Daily)","posX":40,"posY":20,"width":600,"height":50}'::jsonb,
     0),
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'FRAME', NULL,
     '{"title":"Météo équipe","posX":40,"posY":100,"width":260,"height":180,"color":"#FEF9C3"}', 1),
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'FRAME', NULL,
     '{"title":"Indicateurs clés","posX":320,"posY":100,"width":260,"height":180,"color":"#DBEAFE"}', 2),
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'FRAME', NULL,
     '{"title":"Irritants / Obstacles","posX":600,"posY":100,"width":260,"height":180,"color":"#FEE2E2"}', 3),
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'FRAME', NULL,
     '{"title":"Actions","posX":880,"posY":100,"width":260,"height":180,"color":"#DCFCE7"}', 4),
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'CARD', NULL,
     '{"type":"LABEL","content":"Kanban équipe","posX":40,"posY":320,"width":300,"height":40,"color":"#F1F5F9"}',
     5),
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'FRAME', NULL,
     '{"title":"À faire","posX":40,"posY":380,"width":260,"height":340,"color":"#F1F5F9"}', 6),
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'FRAME', NULL,
     '{"title":"En cours","posX":320,"posY":380,"width":260,"height":340,"color":"#DBEAFE"}', 7),
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'FRAME', NULL,
     '{"title":"Bloqué","posX":600,"posY":380,"width":260,"height":340,"color":"#FEE2E2"}', 8),
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'FRAME', NULL,
     '{"title":"Terminé","posX":880,"posY":380,"width":260,"height":340,"color":"#DCFCE7"}', 9),
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'FIELD', NULL,
     '{"name":"Statut","type":"SELECT","options":["À faire","En cours","Bloqué","Terminé"],"order":0}', 10),
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'FIELD', NULL,
     '{"name":"Responsable","type":"TEXT","order":1}', 11),
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'FIELD', NULL,
     '{"name":"Échéance","type":"DATE","order":2}', 12)
) AS v(template_id, element_type, local_key, payload, display_order)
WHERE NOT EXISTS (
    SELECT 1 FROM collaboratif.whiteboard_template_element e
    WHERE e.template_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid
);
