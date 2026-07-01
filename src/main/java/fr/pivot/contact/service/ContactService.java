package fr.pivot.contact.service;

import fr.pivot.auth.service.EmailService;
import fr.pivot.contact.dto.ContactRequestDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Orchestrates contact form processing: sends a confirmation to the sender
 * and a notification to the support team.
 */
@Service
public class ContactService {

    private static final Logger LOG = LoggerFactory.getLogger(ContactService.class);

    private final EmailService emailService;
    private final String ownerEmail;
    private final Locale ownerLocale;

    public ContactService(
            final EmailService emailService,
            @Value("${pivot.app.owner-mail:support@pivot.app}") final String ownerEmail,
            @Value("${pivot.app.owner-lang:fr}") final String ownerLang) {
        this.emailService = emailService;
        this.ownerEmail = ownerEmail;
        this.ownerLocale = EmailService.toLocale(ownerLang);
    }

    /**
     * Sends a confirmation email to the sender (in the sender's language) and a
     * notification to the owner (in the owner's configured language).
     *
     * @param dto validated contact request
     */
    public void processContact(final ContactRequestDto dto) {
        final Locale senderLocale = EmailService.toLocale(dto.lang());
        emailService.sendContactConfirmation(dto.email(), dto.message(), senderLocale);
        emailService.sendContactNotification(ownerEmail, dto.email(), dto.message(), ownerLocale);
        LOG.info("contact_received from=\"{}\"", dto.email());
    }
}
