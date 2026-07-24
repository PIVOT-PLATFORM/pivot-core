package fr.pivot.collaboratif.session;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates 6-character alphanumeric uppercase join codes for sessions (US19.1.1), unique among
 * non-{@link SessionStatus#COMPLETED} sessions of a given tenant.
 *
 * <p><strong>New, collaboratif-local implementation, module-qualified name.</strong> This module
 * never imports {@code fr.pivot.agilite.retro.session.JoinCodeGenerator} (a same-shape sibling in
 * the {@code agilite} module), since cross-module reach-through between {@code agilite} and
 * {@code collaboratif} is forbidden and enforced by {@code ModularityTests}. The alphabet
 * excludes ambiguous characters ({@code 0/O/1/I}, per this US's own AC — a stricter requirement
 * than {@code agilite.retro}'s generator, which deliberately keeps them since its codes are
 * copy-pasted, never dictated).
 *
 * <p><strong>Named {@code SessionJoinCodeGenerator}, not the bare {@code JoinCodeGenerator}</strong> —
 * agilite's sibling class above has the exact same simple name in a different package; both are
 * aggregated into the same {@code pivot-core-app} classpath, where Spring's default
 * annotation-based bean-name generator derives a bean id from the simple class name
 * (decapitalized), so two distinct {@code @Component} classes named {@code JoinCodeGenerator}
 * collide on the identical bean id {@code joinCodeGenerator} and fail context startup with a
 * {@code ConflictingBeanDefinitionException} — the exact same collision class already documented
 * on {@link fr.pivot.collaboratif.context.CollaboratifRequestPrincipal}/{@code
 * CollaboratifRequestPrincipalResolver}/{@code CollaboratifExceptionHandler}, discovered here only
 * once this module was aggregated into the full reactor build (an isolated {@code -pl
 * collaboratif} build never exercises the collision).
 *
 * <p><strong>Randomness.</strong> Uses {@link SecureRandom}, never {@code Random}/{@code
 * Math.random()}.
 *
 * <p><strong>Collision handling.</strong> Checks {@link
 * SessionRepository#existsByTenantIdAndJoinCodeAndStatusNot} before returning a candidate,
 * retrying up to {@value #MAX_ATTEMPTS} times (US19.1.1 AC: bounded to 10 attempts) rather than
 * attempting an insert and catching a constraint violation — the database's own partial unique
 * index ({@code uq_session_join_code_active}) remains the authoritative correctness guarantee
 * regardless of this pre-check.
 */
@Component
public class SessionJoinCodeGenerator {

    /** Alphabet used to build join codes: uppercase letters and digits, excluding 0/O/1/I. */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** Fixed length of every generated join code. */
    private static final int CODE_LENGTH = 6;

    /** Maximum number of collision-retry attempts before giving up (US19.1.1 AC). */
    private static final int MAX_ATTEMPTS = 10;

    private final SessionRepository sessionRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Constructs the generator with its collision-check dependency.
     *
     * @param sessionRepository repository used to check candidate codes for uniqueness
     */
    public SessionJoinCodeGenerator(final SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * Generates a join code guaranteed not to collide with an existing active session's code, for
     * the given tenant, at the time of the check.
     *
     * @param tenantId the tenant to scope the uniqueness check to
     * @return a 6-character alphanumeric join code
     * @throws IllegalStateException if {@value #MAX_ATTEMPTS} consecutive attempts all collided
     */
    public String generate(final Long tenantId) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = randomCode();
            if (!sessionRepository.existsByTenantIdAndJoinCodeAndStatusNot(
                    tenantId, candidate, SessionStatus.COMPLETED)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Unable to generate a unique join code after " + MAX_ATTEMPTS + " attempts");
    }

    /**
     * Draws a single random {@value #CODE_LENGTH}-character candidate from {@link #ALPHABET}.
     *
     * @return a freshly drawn candidate code, not yet checked for uniqueness
     */
    private String randomCode() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            builder.append(ALPHABET.charAt(secureRandom.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
