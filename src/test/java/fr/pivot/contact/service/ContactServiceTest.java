package fr.pivot.contact.service;

import fr.pivot.auth.service.EmailService;
import fr.pivot.contact.dto.ContactRequestDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Locale;

import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link ContactService}.
 */
@ExtendWith(MockitoExtension.class)
class ContactServiceTest {

    private static final String TEAM_EMAIL = "team@pivot.app";

    @Mock
    private EmailService emailService;

    private ContactService contactService;

    @BeforeEach
    void setUp() {
        contactService = new ContactService(emailService, TEAM_EMAIL, "fr");
    }

    @Test
    void processContact_sends_confirmation_to_sender_in_french() {
        final var dto = new ContactRequestDto("alice@example.com", "Bonjour", "fr");
        contactService.processContact(dto);
        verify(emailService).sendContactConfirmation("alice@example.com", "Bonjour", Locale.FRENCH);
    }

    @Test
    void processContact_sends_notification_to_team_in_owner_locale() {
        final var dto = new ContactRequestDto("alice@example.com", "Bonjour", "fr");
        contactService.processContact(dto);
        verify(emailService).sendContactNotification(TEAM_EMAIL, "alice@example.com", "Bonjour", Locale.FRENCH);
    }

    @Test
    void processContact_sends_confirmation_to_sender_in_english_when_lang_en() {
        final var dto = new ContactRequestDto("bob@example.com", "Hello", "en");
        contactService.processContact(dto);
        verify(emailService).sendContactConfirmation("bob@example.com", "Hello", Locale.ENGLISH);
    }

    @Test
    void processContact_sends_notification_to_team_in_owner_locale_regardless_of_sender_lang() {
        final var dto = new ContactRequestDto("bob@example.com", "Hello", "en");
        contactService.processContact(dto);
        // Owner locale is "fr" regardless of the sender's language
        verify(emailService).sendContactNotification(TEAM_EMAIL, "bob@example.com", "Hello", Locale.FRENCH);
    }

    @Test
    void processContact_defaults_to_french_for_null_lang() {
        final var dto = new ContactRequestDto("carol@example.com", "Test", null);
        contactService.processContact(dto);
        verify(emailService).sendContactConfirmation("carol@example.com", "Test", Locale.FRENCH);
    }

    @Test
    void processContact_owner_lang_en_sends_notification_in_english() {
        final var service = new ContactService(emailService, TEAM_EMAIL, "en");
        final var dto = new ContactRequestDto("alice@example.com", "Bonjour", "fr");
        service.processContact(dto);
        verify(emailService).sendContactConfirmation("alice@example.com", "Bonjour", Locale.FRENCH);
        verify(emailService).sendContactNotification(TEAM_EMAIL, "alice@example.com", "Bonjour", Locale.ENGLISH);
    }
}
