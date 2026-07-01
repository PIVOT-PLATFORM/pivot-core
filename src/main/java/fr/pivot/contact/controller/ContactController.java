package fr.pivot.contact.controller;

import fr.pivot.auth.exception.RateLimitException;
import fr.pivot.auth.service.RateLimiterService;
import fr.pivot.contact.dto.ContactRequestDto;
import fr.pivot.contact.service.ContactService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Public endpoint for the contact form.
 *
 * <p>No authentication required — protected against abuse via input size limits
 * and IP-based rate limiting (5 requests per 10 minutes per IP).
 * Emails are sent asynchronously; the response is immediate (202 Accepted).
 */
@RestController
@RequestMapping("/contact")
public class ContactController {

    private static final int MAX_SUBMISSIONS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final ContactService contactService;
    private final RateLimiterService rateLimiter;

    public ContactController(final ContactService contactService,
                             final RateLimiterService rateLimiter) {
        this.contactService = contactService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Processes a contact form submission.
     *
     * @param dto     validated contact form payload
     * @param request used to extract the client IP for rate limiting
     * @throws RateLimitException if the IP exceeds 5 submissions per 10 minutes
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void submit(@Valid @RequestBody final ContactRequestDto dto,
                       final HttpServletRequest request) {
        final String bucket = rateLimiter.contactIpBucket(request.getRemoteAddr());
        if (!rateLimiter.checkAndRecord(bucket, MAX_SUBMISSIONS, WINDOW)) {
            throw new RateLimitException(rateLimiter.getRemainingSeconds(bucket));
        }
        contactService.processContact(dto);
    }
}
