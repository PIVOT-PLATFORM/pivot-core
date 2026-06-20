# ADR-002 — Licence AGPL-3.0

**Statut :** Accepté  
**Date :** 2026-06-19  
**Décideurs :** Alexandre Solane

---

## Contexte

PIVOT est une suite collaborative destinée à être auto-hébergée. Il faut choisir une licence open-source qui encourage les contributions sans permettre à des acteurs commerciaux de forker le projet et de le distribuer en SaaS fermé sans reverser leurs modifications.

## Options évaluées

| Licence | Copyleft réseau | Permet SaaS fermé | Contributions requises |
|---------|----------------|-------------------|----------------------|
| MIT | ❌ | ✅ (faille SaaS) | Non |
| GPL-3.0 | ❌ (distribution uniquement) | ✅ (faille SaaS) | Non |
| AGPL-3.0 | ✅ | ❌ | Oui |
| Apache 2.0 | ❌ | ✅ | Non |

## Décision

**AGPL-3.0** — la clause "usage réseau" impose à quiconque exploite une version modifiée de PIVOT via un réseau (y compris en SaaS) de publier ses modifications sous la même licence.

## Conséquences

**Positives :**
- Forks commerciaux SaaS obligés de publier leurs modifications
- Encourage le retour de contributions vers le projet principal
- Compatible avec l'hébergement self-hosted par des organisations

**Négatives / Contraintes :**
- Incompatible avec certaines bibliothèques propriétaires (non-problème ici — stack full open-source)
- Les clients entreprise qui veulent une licence commerciale sans copyleft devront négocier une licence duale (option future)
- Certains contributeurs peuvent être réticents à contribuer sous AGPL
