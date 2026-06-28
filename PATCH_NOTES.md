# Notes de version — PIVOT Core

## [1.0.0] — 28 juin 2026

---

## [0.0.0] — 28 juin 2026

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
