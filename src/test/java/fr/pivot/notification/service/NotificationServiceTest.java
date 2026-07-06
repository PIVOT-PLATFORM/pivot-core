package fr.pivot.notification.service;

import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.notification.dto.NotificationDto;
import fr.pivot.notification.entity.Notification;
import fr.pivot.notification.event.NotificationCreatedEvent;
import fr.pivot.notification.exception.NotificationNotFoundException;
import fr.pivot.notification.repository.NotificationRepository;
import fr.pivot.tenant.entity.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires pour {@link NotificationService} (EN-NOTIF).
 *
 * <p>L'isolation tenant en base (jointures {@code user_id}/{@code tenant_id} réelles) n'est pas
 * exerçable ici (pas d'{@code EntityManager} réel) — couverte par les tests d'intégration
 * Testcontainers. Ce test vérifie que le service transmet bien {@code userId}/{@code tenantId}
 * tels quels au repository, jamais un autre identifiant, et le mécanisme de résolution i18n /
 * publication d'événement.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long TENANT_ID = 42L;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MessageSource messageSource;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(notificationRepository, userRepository, messageSource, eventPublisher);
    }

    // ----------------------------------------------------------------
    // create(userId, type, payload)
    // ----------------------------------------------------------------

    @Test
    void create_resolvesTitleAndBodyInRecipientLocale_andPersists() {
        final User user = userWithLocale(USER_ID, TENANT_ID, "en");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(messageSource.getMessage(eq("notification.role-changed.title"), any(), eq(Locale.ENGLISH)))
                .thenReturn("Your role has changed");
        when(messageSource.getMessage(eq("notification.role-changed.body"), any(), eq(Locale.ENGLISH)))
                .thenReturn("Your role is now ROLE_ADMIN.");
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        final Notification result = service.create(
                USER_ID, NotificationType.ROLE_CHANGED, NotificationPayload.of("ROLE_ADMIN"));

        assertThat(result.getTitle()).isEqualTo("Your role has changed");
        assertThat(result.getBody()).isEqualTo("Your role is now ROLE_ADMIN.");
        assertThat(result.getUser()).isEqualTo(user);
        assertThat(result.getTenant()).isEqualTo(user.getTenant());
        assertThat(result.getType()).isEqualTo(NotificationType.ROLE_CHANGED);
        verify(notificationRepository).save(any(Notification.class));
    }

    @Test
    void create_defaultsToFrenchLocale_whenUserLocaleIsNull() {
        final User user = userWithLocale(USER_ID, TENANT_ID, null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(USER_ID, NotificationType.ACCOUNT_DEACTIVATED, NotificationPayload.of());

        verify(messageSource).getMessage(eq("notification.account-deactivated.title"), any(), eq(Locale.FRENCH));
        verify(messageSource).getMessage(eq("notification.account-deactivated.body"), any(), eq(Locale.FRENCH));
    }

    @Test
    void create_publishesNotificationCreatedEvent_afterPersisting() {
        final User user = userWithLocale(USER_ID, TENANT_ID, "fr");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(USER_ID, NotificationType.ACCOUNT_DEACTIVATED, NotificationPayload.of());

        final ArgumentCaptor<NotificationCreatedEvent> captor =
                ArgumentCaptor.forClass(NotificationCreatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().notification()).isInstanceOf(NotificationDto.class);
    }

    @Test
    void create_throwsIllegalArgument_whenUserIdUnknown() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(999L, NotificationType.ROLE_CHANGED, NotificationPayload.of("X")))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(notificationRepository, eventPublisher);
    }

    // ----------------------------------------------------------------
    // list(userId, tenantId, pageable)
    // ----------------------------------------------------------------

    @Test
    void list_delegatesToRepositoryWithUserAndTenantId_andMapsToDto() {
        final Notification notification = persistedNotification();
        final Pageable pageable = mock(Pageable.class);
        when(notificationRepository.findByUserIdAndTenantId(USER_ID, TENANT_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(notification)));

        final Page<NotificationDto> page = service.list(USER_ID, TENANT_ID, pageable);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).title()).isEqualTo(notification.getTitle());
        assertThat(page.getContent().get(0).type()).isEqualTo(notification.getType());
    }

    // ----------------------------------------------------------------
    // unreadCount(userId, tenantId)
    // ----------------------------------------------------------------

    @Test
    void unreadCount_delegatesToRepositoryWithUserAndTenantId() {
        when(notificationRepository.countByUserIdAndTenantIdAndReadAtIsNull(USER_ID, TENANT_ID)).thenReturn(3L);

        assertThat(service.unreadCount(USER_ID, TENANT_ID)).isEqualTo(3L);
    }

    // ----------------------------------------------------------------
    // markAsRead(notificationId, userId)
    // ----------------------------------------------------------------

    @Test
    void markAsRead_setsReadAt_whenCurrentlyUnread() {
        final Notification notification = persistedNotification();
        when(notificationRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(notification));

        final NotificationDto dto = service.markAsRead(1L, USER_ID);

        assertThat(notification.getReadAt()).isNotNull();
        assertThat(dto.readAt()).isNotNull();
        verify(notificationRepository).save(notification);
    }

    @Test
    void markAsRead_isIdempotent_whenAlreadyRead() {
        final Notification notification = persistedNotification();
        final Instant firstReadAt = Instant.parse("2026-01-01T00:00:00Z");
        notification.setReadAt(firstReadAt);
        when(notificationRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(notification));

        final NotificationDto dto = service.markAsRead(1L, USER_ID);

        assertThat(dto.readAt()).isEqualTo(firstReadAt);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void markAsRead_throwsNotFound_whenNotificationDoesNotBelongToCaller() {
        when(notificationRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markAsRead(1L, USER_ID))
                .isInstanceOf(NotificationNotFoundException.class);
    }

    // ----------------------------------------------------------------
    // markAllAsRead(userId, tenantId)
    // ----------------------------------------------------------------

    @Test
    void markAllAsRead_delegatesToRepository_andReturnsUpdatedCount() {
        when(notificationRepository.markAllAsRead(eq(USER_ID), eq(TENANT_ID), any(Instant.class))).thenReturn(5);

        final int updated = service.markAllAsRead(USER_ID, TENANT_ID);

        assertThat(updated).isEqualTo(5);
        verify(notificationRepository, times(1)).markAllAsRead(eq(USER_ID), eq(TENANT_ID), any(Instant.class));
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private static User userWithLocale(final Long userId, final Long tenantId, final String locale) {
        final Tenant tenant = mock(Tenant.class);
        // lenient() : ce helper est partagé par des tests qui n'exercent pas tous les mêmes
        // champs (ex. list_/markAsRead_ ne lisent jamais la locale) — Mockito STRICT_STUBS
        // lèverait UnnecessaryStubbingException sur les champs non lus par un test donné.
        lenient().when(tenant.getId()).thenReturn(tenantId);

        final User user = mock(User.class);
        lenient().when(user.getId()).thenReturn(userId);
        lenient().when(user.getTenant()).thenReturn(tenant);
        lenient().when(user.getLocale()).thenReturn(locale);
        return user;
    }

    private Notification persistedNotification() {
        final User user = userWithLocale(USER_ID, TENANT_ID, "fr");
        final Notification notification = new Notification();
        notification.setUser(user);
        notification.setTenant(user.getTenant());
        notification.setType(NotificationType.ROLE_CHANGED);
        notification.setTitle("Votre rôle a été modifié");
        notification.setBody("Votre rôle est désormais ROLE_ADMIN.");
        return notification;
    }
}
