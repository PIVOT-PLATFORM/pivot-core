package fr.pivot.collaboratif.whiteboard.member.dto;

import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for inviting a user by e-mail (POST /whiteboard/boards/{boardId}/members,
 * US08.2.5).
 *
 * @param email the invitee's e-mail — must be a syntactically valid, non-blank address
 * @param role  the role granted; {@code null} defaults to {@link BoardRole#VIEWER} at the service
 *              layer. {@link BoardRole#OWNER} is always rejected — ownership transfer is out of
 *              scope, matching {@code UpdateMemberRoleRequest}.
 */
public record InviteMemberRequest(
        @NotBlank(message = "INVALID_EMAIL")
        @Email(message = "INVALID_EMAIL")
        @Size(max = 320, message = "INVALID_EMAIL")
        String email,
        BoardRole role) {
}
