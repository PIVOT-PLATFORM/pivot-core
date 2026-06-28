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
    private final String teamEmail;

    public ContactService(
            final EmailService emailService,
            @Value("${pivot.app.support-email:support@pivot.app}") final String teamEmail) {
        this.emailService = emailService;
        this.teamEmail = teamEmail;
    }

    /**
     * Sends a confirmation email to the sender and a notification to the team.
     *
     * @param dto validated contact request
     */
    public void processContact(final ContactRequestDto dto) {
        final Locale locale = EmailService.toLocale(dto.lang());
        emailService.sendContactConfirmation(dto.email(), dto.message(), locale);
        emailService.sendContactNotification(teamEmail, dto.email(), dto.message(), locale);
        LOG.info("contact_received from=\"{}\"", dto.email());
    }
}
