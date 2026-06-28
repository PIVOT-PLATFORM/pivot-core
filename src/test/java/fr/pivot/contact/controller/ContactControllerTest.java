package fr.pivot.contact.controller;

import fr.pivot.contact.dto.ContactRequestDto;
import fr.pivot.contact.service.ContactService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link ContactController}.
 */
@ExtendWith(MockitoExtension.class)
class ContactControllerTest {

    @Mock
    private ContactService contactService;

    @InjectMocks
    private ContactController controller;

    @Test
    void submit_delegates_to_service() {
        final var dto = new ContactRequestDto("alice@example.com", "Hello team", "fr");
        controller.submit(dto);
        verify(contactService).processContact(dto);
    }

    @Test
    void submit_accepts_null_lang() {
        final var dto = new ContactRequestDto("alice@example.com", "Hi", null);
        controller.submit(dto);
        verify(contactService).processContact(any());
    }
}
