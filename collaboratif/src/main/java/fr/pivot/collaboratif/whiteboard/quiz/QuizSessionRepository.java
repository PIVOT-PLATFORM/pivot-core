package fr.pivot.collaboratif.whiteboard.quiz;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link QuizSession} entities (Quiz feature).
 *
 * <p>Every lookup that precedes a mutation scopes explicitly by {@code (id, boardId, tenantId)} —
 * never {@code id} alone — so a session id belonging to another board or tenant (guessed or
 * leaked) never resolves here, consistent with the rest of this module's anti-IDOR convention (see
 * {@code VoteSessionRepository}, the model this repository mirrors).
 */
public interface QuizSessionRepository extends JpaRepository<QuizSession, UUID> {

    /**
     * Returns the current {@link QuizStatus#ACTIVE} session for a board within a tenant, if any.
     * At most one such session exists (see the partial unique index in {@code V9__quiz.sql}).
     *
     * @param boardId  the board UUID
     * @param tenantId the tenant's {@code public.tenants.id} (tenant isolation)
     * @param status   the status to filter on (always {@link QuizStatus#ACTIVE} at the call site)
     * @return the active session, or empty if none is active
     */
    Optional<QuizSession> findByBoardIdAndTenantIdAndStatus(UUID boardId, Long tenantId, QuizStatus status);

    /**
     * Returns whether an {@link QuizStatus#ACTIVE} session already exists for the given board —
     * the application-level single-active-session guard used before starting a new one.
     *
     * @param boardId the board UUID
     * @param status  the status to test (always {@link QuizStatus#ACTIVE} at the call site)
     * @return {@code true} if a session with that status exists on the board
     */
    boolean existsByBoardIdAndStatus(UUID boardId, QuizStatus status);

    /**
     * Returns the most recently created session of the given status for a board within a tenant —
     * used by {@code GET .../quiz/last} to expose the last {@link QuizStatus#CLOSED} session.
     *
     * @param boardId  the board UUID
     * @param tenantId the tenant's {@code public.tenants.id} (tenant isolation)
     * @param status   the status to filter on
     * @return the newest matching session, or empty if none exists
     */
    Optional<QuizSession> findFirstByBoardIdAndTenantIdAndStatusOrderByCreatedAtDesc(
            UUID boardId, Long tenantId, QuizStatus status);

    /**
     * Returns a session scoped by board and tenant, acquiring a pessimistic write lock on the row
     * for the duration of the surrounding transaction. This serialises concurrent
     * {@code quiz:answer}/{@code quiz:next}/{@code quiz:reveal}/{@code quiz:stop} operations on the
     * same session, making the upsert-then-broadcast and state-transition sequences atomic — the
     * core anti-IDOR + anti-race primitive of the module (see {@code V9__quiz.sql}).
     *
     * @param id       the session UUID
     * @param boardId  the owning board UUID
     * @param tenantId the tenant's {@code public.tenants.id}
     * @return the locked session if it exists and belongs to this board/tenant; empty otherwise
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM QuizSession s WHERE s.id = :id AND s.boardId = :boardId AND s.tenantId = :tenantId")
    Optional<QuizSession> findForUpdate(
            @Param("id") UUID id, @Param("boardId") UUID boardId, @Param("tenantId") Long tenantId);
}
