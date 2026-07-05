# Notes de version — PIVOT Core

## [0.13.0] — 5 juillet 2026

### Export de vos données personnelles

- **Demander un export de vos données** : depuis votre espace compte, vous pouvez désormais demander une archive complète de vos données personnelles (profil, sessions actives, historique d'activité) — conformément à votre droit à la portabilité
- **Génération en arrière-plan** : la demande est traitée immédiatement en arrière-plan, sans bloquer votre navigation ; un email contenant le lien de téléchargement vous est envoyé dès que l'archive est prête
- **Téléchargement sécurisé** : le lien de téléchargement n'est valable que 24 heures et nécessite d'être connecté à votre compte — il ne peut pas être utilisé par quelqu'un d'autre
- **Une demande à la fois** : une nouvelle demande d'export ne peut être faite que toutes les 24 heures

---

## [0.12.0] — 5 juillet 2026

### Sécurité du compte

- **Sessions actives** : depuis votre espace compte, vous pouvez désormais consulter la liste de vos sessions actives (appareil, adresse IP, date de création, date d'expiration) et repérer clairement celle que vous utilisez actuellement
- **Révocation à distance** : possibilité de déconnecter une session précise, ou toutes les autres sessions en une seule action, pour couper court à un accès non autorisé — la session en cours ne peut pas être auto-révoquée par erreur
- **Nom d'appareil sécurisé** : le nom d'appareil associé à chaque session est systématiquement nettoyé (balises HTML supprimées, longueur limitée) avant d'être enregistré

---

## [0.11.0] — 5 juillet 2026

### Sécurité du compte

- **Changer son adresse email** : depuis votre compte, vous pouvez désormais demander à changer votre adresse email en confirmant votre mot de passe actuel — un lien de confirmation valable 24 heures est envoyé à la nouvelle adresse, et votre ancienne adresse reste active tant que vous n'avez pas cliqué dessus
- **Notification de sécurité** : dès que le changement est confirmé, un email est envoyé à votre ancienne adresse pour vous en informer, avec un lien pour sécuriser votre compte si vous n'êtes pas à l'origine de la demande
- **Une seule demande à la fois** : si vous refaites une demande de changement d'email, la précédente est automatiquement annulée
- **Protection contre les abus** : un lien de confirmation ne peut être utilisé qu'une seule fois, et le nombre de demandes est limité à 3 par heure
- **Message d'erreur clair en cas de conflit rare** : si l'adresse visée vient d'être prise par quelqu'un d'autre au moment exact de la confirmation, un message d'erreur explicite s'affiche au lieu d'une erreur technique générique

---

## [0.10.0] — 5 juillet 2026

### Espace compte

- **Consulter et modifier son profil** : chaque utilisateur peut désormais consulter et modifier son prénom et son nom depuis son espace compte (la modification de l'adresse email n'est pas encore disponible ici et fera l'objet d'une fonctionnalité dédiée)
- **Photo de profil** : possibilité d'ajouter ou de remplacer sa photo de profil (JPEG, PNG ou WEBP, 2 Mo maximum) ; sans photo, vos initiales s'affichent à la place
- **Sécurité** : les prénoms et noms sont nettoyés de tout code indésirable avant d'être affichés à d'autres utilisateurs

---

## [0.9.0] — 5 juillet 2026

### Espace compte

- **Changer son mot de passe** : depuis votre espace compte, vous pouvez désormais changer votre mot de passe en confirmant votre mot de passe actuel — le nouveau mot de passe doit respecter la politique de robustesse en vigueur
- **Sécurité renforcée à chaque changement** : toutes vos autres sessions actives (autres appareils, autres navigateurs) sont automatiquement déconnectées, tandis que votre session en cours reste active sans interruption
- **Email de confirmation** : un email vous est envoyé à chaque changement de mot de passe réussi, avec la date et l'adresse IP à l'origine du changement, pour vous permettre de détecter rapidement une activité suspecte
- **Protection contre les tentatives répétées** : au-delà de 5 tentatives infructueuses en 15 minutes, l'accès à cette fonctionnalité est temporairement limité

---

## [0.8.0] — 5 juillet 2026

### Administration des utilisateurs

- **Liste des utilisateurs de l'organisation** : les administrateurs peuvent désormais consulter la liste paginée des utilisateurs de leur organisation depuis l'API, avec recherche par nom ou e-mail et filtres par rôle ou statut du compte
- **Filtre par rôle plus explicite** : une valeur de rôle inconnue est désormais rejetée clairement (comme c'est déjà le cas pour le filtre par statut) plutôt que de renvoyer silencieusement une liste vide

---

## [0.7.0] — 5 juillet 2026

### Supervision plateforme

- **Liste des tenants pour les super-administrateurs** : nouvel écran de supervision permettant aux super-administrateurs de consulter tous les clients de la plateforme (nom, plan, mode d'authentification, statut, nombre d'utilisateurs, date de création), avec recherche par nom et filtres par statut, plan et mode d'authentification
- **Taille de page plafonnée** : la liste des tenants (et toute future liste paginée de l'administration plateforme) ne peut plus être interrogée avec une taille de page excessive — la taille demandée est automatiquement ramenée à un maximum raisonnable

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
