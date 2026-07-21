package fr.pivot.collaboratif.whiteboard.member.dto;

import fr.pivot.collaboratif.whiteboard.board.BoardMember;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.member.UserDirectoryEntry;

import java.time.Instant;

/**
 * Read-only DTO representing a board member's identity, role, and join timestamp.
 *
 * <p>The identity fields ({@code email}, {@code firstName}, {@code lastName}, {@code avatarUrl})
 * are {@code null} when the member's {@code public.users} row cannot be resolved — a deleted,
 * deactivated, or foreign-tenant account. The frontend renders such a row as an unknown member
 * showing {@code #<userId>}. Exposed only to callers already granted board access
 * (see {@code BoardMemberService.requireAccess}).
 *
 * @param userId    the member's {@code public.users.id}
 * @param email     the member's e-mail, or {@code null} if the account is unresolved
 * @param firstName the member's first name, or {@code null}
 * @param lastName  the member's last name, or {@code null}
 * @param avatarUrl the member's avatar URL, or {@code null}
 * @param role      the member's current role on the board
 * @param joinedAt  the instant the user joined the board
 */
public record MemberResponse(
        Long userId,
        String email,
        String firstName,
        String lastName,
        String avatarUrl,
        BoardRole role,
        Instant joinedAt) {

    /**
     * Builds a response with no resolved identity (identity fields {@code null}). Used when the
     * directory entry is unavailable, and for endpoints whose callers do not consume identity
     * (role update).
     *
     * @param member the board member entity
     * @return the response record with null identity fields
     */
    public static MemberResponse from(final BoardMember member) {
        return from(member, null);
    }

    /**
     * Builds a response enriched with directory identity.
     *
     * @param member the board member entity
     * @param entry  the resolved directory entry, or {@code null} if the user is unknown
     * @return the response record
     */
    public static MemberResponse from(final BoardMember member, final UserDirectoryEntry entry) {
        return new MemberResponse(
                member.getId().getUserId(),
                entry != null ? entry.getEmail() : null,
                entry != null ? entry.getFirstName() : null,
                entry != null ? entry.getLastName() : null,
                entry != null ? entry.getAvatarUrl() : null,
                member.getRole(),
                member.getJoinedAt());
    }
}
