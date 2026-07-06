/**
 * Listeners qui relient des événements externes à
 * {@link fr.pivot.notification.service.NotificationService} (EN-NOTIF).
 *
 * <p><strong>Câblé et réel dès cet enabler :</strong>
 * <ul>
 *   <li>{@link fr.pivot.notification.listener.NotificationPushListener} — consomme
 *       l'événement interne {@link fr.pivot.notification.event.NotificationCreatedEvent}
 *       (publié par {@code NotificationService#create}) pour le push STOMP
 *       ({@code /user/{userId}/queue/notifications}), en {@code AFTER_COMMIT}.</li>
 *   <li>US06.1.3 (changement de rôle) et US06.1.4 (désactivation de compte) — pas de listener
 *       ici : {@code fr.pivot.auth.service.AdminUserService} appelle directement
 *       {@code NotificationService#create} (déjà fusionné sur {@code main}, pas d'événement à
 *       écouter — voir {@link fr.pivot.notification.service.NotificationType}).</li>
 * </ul>
 *
 * <p><strong>Points d'intégration documentés, non câblés — voir EN-NOTIF AC « producteurs
 * définis » US01.5.1 / US01.4.3a</strong>. Les deux branches productrices existent (PR ouvertes,
 * lues en diff au moment de l'implémentation de cet enabler) mais aucune ne publie
 * d'{@code ApplicationEvent} aujourd'hui — il n'y a donc rien à écouter avec un
 * {@code @EventListener} réel sans inventer une classe d'événement qui n'existe pas encore sur
 * ces branches. Pas de tâche en suspens laissée sans trace : le point d'intégration exact est
 * documenté ci-dessous, y compris pour un futur agent qui reprendrait ces branches après fusion.
 *
 * <ul>
 *   <li><strong>US01.5.1</strong> — core PR <a href="https://github.com/PIVOT-PLATFORM/pivot-core/pull/154">#154</a>
 *       (branche {@code feat/us01-5-1-email-action-sensible}, OPEN au moment de cet enabler).
 *       Introduit {@code fr.pivot.auth.service.SecurityNotificationService}, appelé
 *       <em>directement</em> (pas via événement) par {@code AccountPasswordService},
 *       {@code EmailChangeService}, {@code AccountDeletionService} et {@code SessionService}
 *       pour les 4 notifications email "action sensible" (mot de passe modifié, email modifié,
 *       suppression de compte demandée, sessions révoquées). Aucun {@code ApplicationEvent}
 *       n'est publié sur cette branche à ce jour.
 *       <p>À la fusion de ce PR, deux options — à trancher par qui l'implémente, aucune n'est
 *       fabriquée ici par anticipation :
 *       <ol>
 *         <li><em>(préférée, plus simple)</em> ajouter un appel direct
 *             {@code notificationService.create(userId, NotificationType.SENSITIVE_ACTION,
 *             NotificationPayload.of(...))} dans chacune des 4 méthodes de
 *             {@code SecurityNotificationService} — même schéma que
 *             {@code AdminUserService#updateRole}/{@code #updateStatus} ci-dessus ;</li>
 *         <li>ou faire publier par {@code SecurityNotificationService} un événement dédié (ex.
 *             {@code SecurityNotificationSentEvent(userId, action, occurredAt)}) et ajouter ici
 *             un {@code @EventListener} qui traduit cet événement en
 *             {@code NotificationType.SENSITIVE_ACTION}.</li>
 *       </ol>
 *   <li><strong>US01.4.3a</strong> — core PR <a href="https://github.com/PIVOT-PLATFORM/pivot-core/pull/151">#151</a>
 *       (branche {@code feat/us01-4-3a-alerte-connexion-suspecte}, OPEN au moment de cet
 *       enabler). Introduit {@code fr.pivot.auth.service.SuspiciousLoginService}, qui journalise
 *       {@code event=SUSPICIOUS_LOGIN_ALERT_SENT} et envoie un email d'alerte, mais ne publie
 *       de même aucun {@code ApplicationEvent} sur cette branche à ce jour.
 *       <p>À la fusion, câbler {@code NotificationType.UNKNOWN_DEVICE} selon le même choix
 *       (appel direct dans {@code SuspiciousLoginService}, préféré, ou nouvel événement +
 *       listener ici).
 *       <p><strong>Note</strong> — {@code TrustedDeviceRevokedEvent} (introduit par la branche
 *       distincte, également non fusionnée, US01.4.2 / core PR
 *       <a href="https://github.com/PIVOT-PLATFORM/pivot-core/pull/152">#152</a>) est un signal
 *       différent (l'utilisateur révoque lui-même un appareil de confiance) — documenté sur
 *       cette branche comme point de câblage futur pour US01.5.1, pas pour US01.4.3a. Il n'est
 *       pas consommé ici : c'est un événement réel sur une branche réelle, mais hors périmètre
 *       des deux producteurs EN-NOTIF listés dans cette AC, et le fabriquer en listener
 *       spéculatif ici irait à l'encontre de la même règle ("pas de câblage à du code qui
 *       n'existe pas encore sur main").</li>
 * </ul>
 */
package fr.pivot.notification.listener;
