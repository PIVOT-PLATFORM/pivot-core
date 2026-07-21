# Registre de traitement — Capacité (E11)

> **Portée :** module `agilite/.../capacity` (F11 — Capacité, planification de charge PI/sprint).
> Document tenant lieu de fiche de traitement Art. 30 RGPD pour ce module, produit par
> **US11.8.1** (F11.8 — conformité RGPD, Vague 2). Complète (sans le remplacer) le registre
> général de la plateforme.

## Traitement

| Champ | Valeur |
|-------|--------|
| Nom du traitement | Gestion des indisponibilités des membres d'équipe pour le calcul de capacité (PI/sprint) |
| Responsable de traitement | Le tenant (organisation cliente), via ses utilisateurs à rôle `RESPONSABLE`/`ADJOINT` d'équipe |
| Finalité | Calculer la capacité disponible d'une équipe sur un évènement (PI, sprint, release) en tenant compte des périodes d'indisponibilité de ses membres, pour la planification agile |
| Base légale | Intérêt légitime de l'organisation à planifier sa charge d'équipe (ADR-006, ADR-008 — cadrage plateforme du traitement des données d'équipe) ; alternative : exécution du contrat de service liant le tenant à ses collaborateurs pour l'organisation du travail |

## Catégories de données traitées

- **Périodes d'indisponibilité** : date de début, date de fin, fraction (jour entier ou demi-journée), source (saisie manuelle ou import futur).
- **Aucun motif, aucune donnée de santé** : la table `agilite.capacity_absence` ne comporte structurellement aucune colonne de motif ni de nature de l'absence — garantie au niveau du schéma, pas seulement applicative (voir `CapacityAbsence`, javadoc). Le POC dont ce module est porté comportait un champ `reason` ; il a été délibérément supprimé lors du portage.
- **Identité du membre** : un instantané nom/rôle (`agilite.capacity_event_member.name`/`role`), copié depuis `public.team_members` au moment de l'ajout, plus un lien best-effort `team_member_ref` vers cette table (nullable, `ON DELETE SET NULL`).

## Minimisation

- Seules les données strictement nécessaires au calcul de capacité sont collectées : aucune donnée de santé, aucun motif, aucun commentaire libre attaché à une absence.
- Le principe d'**agrégation par défaut** s'applique à toute restitution de ces données dans les vues fonctionnelles courantes du module (jauges, KPI, cartes de synthèse, exports de capacité) : ces restitutions sont **toujours au niveau équipe**, jamais nominatives — aucun payload de `CapacitySummaryController`/`CapacityVelocityController`/`CapacityEventController` n'expose une décomposition absence-par-personne. Seule l'API de droits des personnes (`CapacityRgpdController`, ci-dessous) restitue une donnée nominative, et uniquement à la personne concernée (ou à un responsable d'équipe agissant en son nom), jamais en agrégat de reporting.

## Destinataires

- Les membres de l'équipe à rôle `RESPONSABLE`/`ADJOINT` (écriture) et `MEMBRE` (lecture agrégée) de l'équipe propriétaire de l'évènement de capacité — aucune transmission hors du tenant, aucun sous-traitant tiers.

## Durée de rétention

- Les périodes d'indisponibilité sont conservées tant que l'évènement de capacité auquel elles sont rattachées existe. La suppression d'un membre d'évènement (`DELETE /agilite/capacity/members/{memberId}`) cascade déjà sur ses absences (`ON DELETE CASCADE`).
- Pas de purge automatique programmée à ce jour pour ce module (hors scope US11.8.1) — voir le droit à l'effacement ci-dessous pour la suppression à la demande de la personne concernée.

## Droits des personnes (US11.8.1)

Exposés par `CapacityRgpdController` (`fr.pivot.agilite.capacity.rgpd`), sous
`/api/agilite/capacity/rgpd` :

| Droit | Endpoint | Auth |
|-------|----------|------|
| Accès / portabilité (Art. 15/20) | `GET /members/{teamMemberRef}/data` | Appelant membre de la même équipe que la personne visée (tenant + appartenance équipe, sinon 404) |
| Effacement (Art. 17) | `DELETE /members/{teamMemberRef}/data` | Idem, plus rôle `RESPONSABLE`/`ADJOINT` requis (sinon 403) |

`teamMemberRef` désigne `public.team_members.id` — la même référence que celle capturée par
`CapacityEventMember.teamMemberRef` à l'ajout d'un membre. L'export retourne l'intégralité des
périodes d'indisponibilité de la personne, tous évènements de capacité confondus, pour les
équipes que l'appelant partage avec elle dans son tenant ; l'effacement supprime ces mêmes lignes
`agilite.capacity_absence`. Ni l'un ni l'autre n'opère sur `public.team_members` lui-même (hors
périmètre de ce module) ni sur une quelconque donnée de motif/santé, qui n'existe pas dans ce
schéma.
