# Notes de version — PIVOT Core

## [Unreleased]

### Supervision plateforme

- **Liste des tenants pour les super-administrateurs** : nouvel écran de supervision permettant aux super-administrateurs de consulter tous les clients de la plateforme (nom, plan, mode d'authentification, statut, nombre d'utilisateurs, date de création), avec recherche par nom et filtres par statut, plan et mode d'authentification

---

## [0.6.0] — 3 juillet 2026

### Registre des modules

- **Statut d'un module précis** : nouvel endpoint `GET /api/modules/{id}/status` — permet à l'application de vérifier en temps réel si un module précis est activé pour votre organisation avant d'y donner accès, sans mise en cache côté navigateur, et sans jamais attendre plus de quelques secondes après une activation/désactivation par un administrateur

---

## [0.5.0] — 3 juillet 2026

### Registre des modules

- **Activation des modules par les administrateurs** : les administrateurs peuvent désormais activer et désactiver les modules pour leur organisation depuis l'API — chaque changement est tracé dans le journal d'audit

---

## [0.4.0] — 3 juillet 2026

### Sécurité des mots de passe

- **Politique de robustesse renforcée** : tout nouveau mot de passe (inscription ou réinitialisation) doit désormais compter au moins 12 caractères, dont une majuscule, un chiffre et un caractère spécial
- **Règles configurables** : les administrateurs de la plateforme peuvent ajuster ces exigences (longueur minimale, nombre de majuscules, de chiffres et de caractères spéciaux)
- **Règles consultables** : les critères en vigueur sont exposés publiquement afin que le formulaire d'inscription affiche toujours les exigences exactes

---

## [0.3.0] — 3 juillet 2026

### Registre des modules

- **Réactivité de l'activation des modules** : la vérification qu'un module est activé pour une organisation passe désormais par un cache mémoire — un changement d'activation par un administrateur est pris en compte immédiatement, sans délai

---

## [0.2.0] — 3 juillet 2026

### Registre des modules

- **Liste des modules** : nouvel endpoint `GET /api/modules` — retourne l'état de chaque module activable (activé ou désactivé) pour l'utilisateur connecté
- **Socle du système de modules** : la plateforme sait désormais découvrir automatiquement les modules installés (tableau blanc, roadmap, quiz…) et mémoriser, pour chaque organisation, quels modules sont activés ou désactivés — cette base prépare l'activation des modules par les administrateurs
- **Modules additionnels** : les futurs modules pourront s'ajouter à la plateforme sans mise à jour du cœur applicatif
- **Fiabilisation interne** : l'identifiant technique d'organisation utilisé en interne par le système de modules a été uniformisé, et les erreurs sur un module inconnu sont désormais correctement signalées — aucun changement visible pour les utilisateurs

### Formulaire de contact

- **Page de contact publique** : nouveau formulaire accessible sans connexion — champ e-mail, message libre, bouton d'envoi
- **Confirmation automatique** : un e-mail de confirmation est envoyé à l'expéditeur dans sa langue (français ou anglais)
- **Notification interne** : l'équipe reçoit un e-mail de notification avec possibilité de répondre directement à l'expéditeur (Reply-To)
- **Langue configurable** : la langue de notification de l'équipe est indépendante de celle de l'expéditeur (variable `PIVOT_OWNER_LANG`, défaut : français)

---

## [0.1.0] — 28 juin 2026

### Module d'authentification

Première version complète du module d'authentification.

- **Inscription** : création de compte avec vérification d'e-mail obligatoire
- **Connexion** : sessions sécurisées par token opaque, durée configurable par tenant
- **Vérification d'e-mail** : lien de confirmation envoyé à l'inscription, renvoi possible
- **Réinitialisation de mot de passe** : flux complet par e-mail avec lien à usage unique
- **Confirmation d'appareil** : code OTP par e-mail sur tout nouvel appareil non reconnu
- **Protection anti-brute-force** : limitation du nombre de tentatives par IP et par compte
- **E-mails transactionnels** : tous les e-mails disponibles en français et en anglais selon la préférence du compte

### Infrastructure

- Base de données PostgreSQL 18 avec schéma complet (utilisateurs, sessions, appareils de confiance)
- Cache Redis pour la gestion des sessions et du rate-limiting
- Image Docker prête pour la production
