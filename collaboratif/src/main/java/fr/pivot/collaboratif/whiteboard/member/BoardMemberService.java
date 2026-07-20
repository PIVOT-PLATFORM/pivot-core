package fr.pivot.collaboratif.whiteboard.member;

import fr.pivot.collaboratif.exception.BoardAccessDeniedException;
import fr.pivot.collaboratif.exception.BoardMemberNotFoundException;
import fr.pivot.collaboratif.exception.BoardNotFoundException;
import fr.pivot.collaboratif.exception.InvalidInvitationException;
import fr.pivot.collaboratif.exception.InviteeNotFoundException;
import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardMember;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberId;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.member.dto.MemberResponse;
import fr.pivot.notification.service.NotificationPayload;
import fr.pivot.notification.service.NotificationService;
import fr.pivot.notification.service.NotificationType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Business logic for board member management operations, including named e-mail invitations
 * (US08.2.5, extends F08.2).
 *
 * <p>Enforces tenant isolation and role-based access control: only the OWNER may invite, change
 * roles, or remove members. Any caller with access (OWNER, EDITOR, VIEWER) may list members.
 *
 * <p><strong>Notification channel.</strong> State-changing operations emit
 * {@link NotificationType#BOARD_SHARED}/{@code BOARD_ROLE_CHANGED}/{@code BOARD_ACCESS_REVOKED}
 * through the shared platform-wide {@link NotificationService} (EN-NOTIF) — STOMP push, i18n via
 * {@code MessageSource}, {@code GET /notifications} — rather than a whiteboard-local duplicate.
 */
@Service
@Transactional(readOnly = true)
public class BoardMemberService {

    private final BoardRepository boardRepository;
    private final BoardMemberRepository boardMemberRepository;
    private final UserDirectoryRepository userDirectoryRepository;
    private final NotificationService notificationService;

    /**
     * Creates the service with all required dependencies.
     *
     * @param boardRepository         repository for board persistence
     * @param boardMemberRepository   repository for board membership persistence
     * @param userDirectoryRepository read-only e-mail resolution against {@code public.users}
     * @param notificationService     shared platform in-app notification emitter (EN-NOTIF)
     */
    public BoardMemberService(
            final BoardRepository boardRepository,
            final BoardMemberRepository boardMemberRepository,
            final UserDirectoryRepository userDirectoryRepository,
            final NotificationService notificationService) {
        this.boardRepository = boardRepository;
        this.boardMemberRepository = boardMemberRepository;
        this.userDirectoryRepository = userDirectoryRepository;
        this.notificationService = notificationService;
    }

    /**
     * Returns the full member list for a board.
     *
     * <p>Any member (OWNER, EDITOR, VIEWER) may call this. The OWNER entry is included.
     *
     * @param boardId  the board UUID
     * @param callerId the calling user's {@code public.users.id}
     * @param tenantId the calling tenant's {@code public.tenants.id}
     * @return list of all members ordered by join date ascending
     * @throws BoardNotFoundException if the board does not exist, belongs to another tenant,
     *                                or the caller is not a member
     */
    public List<MemberResponse> listMembers(
            final UUID boardId,
            final Long callerId,
            final Long tenantId) {
        Board board = boardRepository.findByIdAndTenantId(boardId, tenantId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
        requireAccess(boardId, callerId, board.getOwnerId());
        return boardMemberRepository.findAllByIdBoardIdOrderByJoinedAtAsc(boardId)
                .stream()
                .map(MemberResponse::from)
                .toList();
    }

    /**
     * Invites a user named by e-mail with a role, upserting the {@code (boardId, userId)}
     * membership; only the OWNER may invoke this (US08.2.5).
     *
     * <p>Refusal order: (1) caller not OWNER → 403/404 (see {@link #requireOwner}), (2) unknown
     * e-mail → 404, (3) self-invitation → 400 — the only manager able to invite is the OWNER, so
     * this single check also covers "e-mail of the board creator" (the two were distinct cases
     * under the original EDITOR-can-manage design; collapsed here since {@link #requireOwner}
     * already guarantees {@code callerId == board.getOwnerId()}), (4) {@code role == OWNER} →
     * rejected (ownership transfer out of scope, matching {@link #updateRole}). On success: a
     * brand-new membership emits {@code BOARD_SHARED}; an existing membership whose role changes
     * emits {@code BOARD_ROLE_CHANGED}; a re-invitation with the same role is a functional no-op
     * with no notification.
     *
     * @param boardId       the board UUID
     * @param email         the invitee's e-mail
     * @param requestedRole the requested role, or {@code null} to default to VIEWER
     * @param callerId      the calling user's {@code public.users.id}
     * @param tenantId      the calling tenant's {@code public.tenants.id}
     * @return the created/updated member
     * @throws BoardNotFoundException     if the board is inaccessible to the caller
     * @throws BoardAccessDeniedException if the caller is not the OWNER
     * @throws InviteeNotFoundException   if the e-mail resolves to no active user of the tenant
     * @throws InvalidInvitationException on self-invitation (the caller inviting their own e-mail)
     * @throws IllegalArgumentException   if the requested role is OWNER
     */
    @Transactional
    public MemberResponse invite(
            final UUID boardId,
            final String email,
            final BoardRole requestedRole,
            final Long callerId,
            final Long tenantId) {
        Board board = boardRepository.findByIdAndTenantId(boardId, tenantId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
        requireOwner(boardId, callerId, board.getOwnerId());
        BoardRole role = requestedRole != null ? requestedRole : BoardRole.VIEWER;
        if (role == BoardRole.OWNER) {
            throw new IllegalArgumentException("Cannot invite a member as OWNER");
        }

        UserDirectoryEntry invitee = userDirectoryRepository
                .findByEmailIgnoreCaseAndTenantIdAndActiveTrue(email, tenantId)
                .orElseThrow(InviteeNotFoundException::new);
        Long inviteeId = invitee.getId();
        if (inviteeId.equals(callerId)) {
            throw new InvalidInvitationException(
                    "SELF_INVITE", "Vous ne pouvez pas vous inviter vous-même");
        }

        BoardMember existing = boardMemberRepository
                .findByIdBoardIdAndIdUserId(boardId, inviteeId)
                .orElse(null);
        if (existing == null) {
            BoardMember created = boardMemberRepository.save(
                    new BoardMember(new BoardMemberId(boardId, inviteeId), role, Instant.now()));
            notificationService.create(inviteeId, NotificationType.BOARD_SHARED,
                    NotificationPayload.of(board.getTitle(), role.name()));
            logAuditEvent("MemberInvited", boardId, callerId,
                    "invitee=" + inviteeId + " role=" + role);
            return MemberResponse.from(created);
        }
        if (existing.getRole() != role) {
            existing.setRole(role);
            BoardMember saved = boardMemberRepository.save(existing);
            notificationService.create(inviteeId, NotificationType.BOARD_ROLE_CHANGED,
                    NotificationPayload.of(board.getTitle(), role.name()));
            logAuditEvent("MemberInviteRoleChanged", boardId, callerId,
                    "invitee=" + inviteeId + " role=" + role);
            return MemberResponse.from(saved);
        }
        return MemberResponse.from(existing);
    }

    /**
     * Changes a member's role; only the OWNER may invoke this.
     *
     * <p>The board owner's own role and promotions to OWNER are both rejected.
     *
     * @param boardId      the board UUID
     * @param targetUserId the {@code public.users.id} of the user whose role is to be changed
     * @param newRole      the new role (EDITOR or VIEWER)
     * @param callerId     the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the updated member response
     * @throws BoardNotFoundException     if the board is inaccessible to the caller
     * @throws BoardAccessDeniedException if the caller is not the OWNER
     * @throws IllegalArgumentException   if targetUserId is the board owner or newRole is OWNER
     * @throws BoardMemberNotFoundException if targetUserId has no membership on this board
     */
    @Transactional
    public MemberResponse updateRole(
            final UUID boardId,
            final Long targetUserId,
            final BoardRole newRole,
            final Long callerId,
            final Long tenantId) {
        Board board = boardRepository.findByIdAndTenantId(boardId, tenantId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
        requireOwner(boardId, callerId, board.getOwnerId());
        if (targetUserId.equals(board.getOwnerId())) {
            throw new IllegalArgumentException("Cannot modify the board owner's role");
        }
        if (newRole == BoardRole.OWNER) {
            throw new IllegalArgumentException("Cannot promote a member to OWNER");
        }
        BoardMember member = boardMemberRepository
                .findByIdBoardIdAndIdUserId(boardId, targetUserId)
                .orElseThrow(() -> new BoardMemberNotFoundException(boardId, targetUserId));
        member.setRole(newRole);
        BoardMember saved = boardMemberRepository.save(member);
        notificationService.create(targetUserId, NotificationType.BOARD_ROLE_CHANGED,
                NotificationPayload.of(board.getTitle(), newRole.name()));
        logAuditEvent("MemberRoleUpdated", boardId, callerId,
                "targetUser=" + targetUserId + " newRole=" + newRole);
        return MemberResponse.from(saved);
    }

    /**
     * Removes a member from the board; only the OWNER may invoke this.
     *
     * <p>The board owner cannot be removed.
     *
     * @param boardId      the board UUID
     * @param targetUserId the {@code public.users.id} of the user to remove
     * @param callerId     the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @throws BoardNotFoundException     if the board is inaccessible to the caller
     * @throws BoardAccessDeniedException if the caller is not the OWNER
     * @throws IllegalArgumentException   if targetUserId is the board owner
     * @throws BoardMemberNotFoundException if targetUserId has no membership on this board
     */
    @Transactional
    public void removeMember(
            final UUID boardId,
            final Long targetUserId,
            final Long callerId,
            final Long tenantId) {
        Board board = boardRepository.findByIdAndTenantId(boardId, tenantId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
        requireOwner(boardId, callerId, board.getOwnerId());
        if (targetUserId.equals(board.getOwnerId())) {
            throw new IllegalArgumentException("Cannot remove the board owner");
        }
        BoardMember member = boardMemberRepository
                .findByIdBoardIdAndIdUserId(boardId, targetUserId)
                .orElseThrow(() -> new BoardMemberNotFoundException(boardId, targetUserId));
        boardMemberRepository.delete(member);
        notificationService.create(targetUserId, NotificationType.BOARD_ACCESS_REVOKED,
                NotificationPayload.of(board.getTitle()));
        logAuditEvent("MemberRemoved", boardId, callerId, "targetUser=" + targetUserId);
    }

    /**
     * Asserts the caller has at least read access (is a member or the owner).
     *
     * @throws BoardNotFoundException if the caller has no membership
     */
    private void requireAccess(final UUID boardId, final Long callerId, final Long ownerId) {
        if (callerId.equals(ownerId)) {
            return;
        }
        boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, callerId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
    }

    /**
     * Asserts the caller is the OWNER.
     *
     * @throws BoardAccessDeniedException if the caller is not the owner
     */
    private void requireOwner(final UUID boardId, final Long callerId, final Long ownerId) {
        requireAccess(boardId, callerId, ownerId);
        if (!callerId.equals(ownerId)) {
            throw new BoardAccessDeniedException(boardId);
        }
    }

    /**
     * Emits a structured audit log entry for a state-changing member operation.
     *
     * @param event   the audit event name
     * @param boardId the board UUID
     * @param actorId the {@code public.users.id} of the user who performed the action
     * @param details additional details to include in the log entry
     */
    private void logAuditEvent(
            final String event,
            final UUID boardId,
            final Long actorId,
            final String details) {
        java.util.logging.Logger.getLogger(getClass().getName())
                .info(() -> "AUDIT " + event + " board=" + boardId
                        + " actor=" + actorId + " " + details);
    }
}
