package fr.pivot.contact.controller;

import fr.pivot.contact.dto.ContactRequestDto;
import fr.pivot.contact.service.ContactService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoint for the contact form.
 *
 * <p>No authentication required — protected against abuse via input size limits.
 * Emails are sent asynchronously; the response is immediate (202 Accepted).
 */
@RestController
@RequestMapping("/api/contact")
public class ContactController {

    private final ContactService contactService;

    public ContactController(final ContactService contactService) {
        this.contactService = contactService;
    }

    /**
     * Processes a contact form submission.
     *
     * @param dto validated contact form payload
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void submit(@Valid @RequestBody final ContactRequestDto dto) {
        contactService.processContact(dto);
    }
}
