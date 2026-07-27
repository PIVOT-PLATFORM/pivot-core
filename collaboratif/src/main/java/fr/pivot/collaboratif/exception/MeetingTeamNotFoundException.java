package fr.pivot.collaboratif.exception;

/**
 * Thrown when a meeting creation request's {@code teamId} does not resolve to a team of the
 * caller's tenant — either the team does not exist at all, or it belongs to another tenant
 * (US12.1.1 AC7). Deliberately indistinguishable between the two cases: mapped to HTTP 404 Not
 * Found by {@link CollaboratifExceptionHandler}, never 403, so a caller cannot use this endpoint
 * to probe for the existence of another tenant's team (anti-enumeration — same posture as {@link
 * KpiNotFoundException}/{@code SessionNotFoundException}).
 */
public class MeetingTeamNotFoundException extends RuntimeException {

    /**
     * Creates a team-not-found exception for the given team id.
     *
     * @param teamId the {@code public.teams.id} that could not be resolved within the caller's
     *               tenant
     */
    public MeetingTeamNotFoundException(final Long teamId) {
        super("Team not found: " + teamId);
    }
}
