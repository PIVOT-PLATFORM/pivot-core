package fr.pivot.collaboratif.whiteboard.quiz;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipalResolver;
import fr.pivot.collaboratif.exception.BoardNotFoundException;
import fr.pivot.collaboratif.exception.CollaboratifExceptionHandler;
import fr.pivot.collaboratif.whiteboard.quiz.dto.QuizSessionResponse;
import fr.pivot.core.auth.AuthenticatedPrincipal;
import fr.pivot.core.auth.AuthenticatedPrincipalResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for {@link QuizController} (Lot D1, whiteboard quiz activity) — a real MockMvc
 * {@code standaloneSetup} (no {@code @SpringBootTest}, no Testcontainers) wiring only the
 * controller under test, a mocked {@link QuizQueryService}, the real {@link
 * CollaboratifRequestPrincipalResolver} argument resolver (backed by a mocked {@link
 * AuthenticatedPrincipalResolver}, exactly as production wiring resolves it from the bearer
 * token) and the real {@link CollaboratifExceptionHandler} — so the HTTP status codes asserted
 * here (200/401/404) are the genuine Spring MVC behaviour, not a hand-rolled substitute.
 *
 * <p><strong>AC → test mapping (§2.3, §7.3):</strong>
 * <ul>
 *   <li>{@code GET .../quiz/current} happy path → {@link #current_validToken_returnsSessionAsJson()}</li>
 *   <li>No active session → {@link #current_noActiveSession_returnsEmptyBody200()}</li>
 *   <li>Anti-IDOR 404 (never 403) → {@link #current_serviceThrowsBoardNotFound_returns404NotFound()}</li>
 *   <li>Missing bearer token → {@link #current_missingAuthorizationHeader_returns401()}</li>
 *   <li>{@code GET .../quiz/last} happy path + 404 → {@link #last_validToken_returnsClosedSessionAsJson()},
 *       {@link #last_serviceThrowsBoardNotFound_returns404NotFound()}</li>
 * </ul>
 */
class QuizControllerTest {

    private static final UUID BOARD_ID = UUID.randomUUID();
    private static final Long USER_ID = 42L;
    private static final Long TENANT_ID = 100L;
    private static final String RAW_TOKEN = "a-raw-bearer-token";

    private QuizQueryService quizQueryService;
    private AuthenticatedPrincipalResolver principalResolver;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        quizQueryService = mock(QuizQueryService.class);
        principalResolver = mock(AuthenticatedPrincipalResolver.class);
        QuizController controller = new QuizController(quizQueryService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new CollaboratifRequestPrincipalResolver(principalResolver))
                .setControllerAdvice(new CollaboratifExceptionHandler())
                .build();
    }

    @Test
    void current_validToken_returnsSessionAsJson() throws Exception {
        when(principalResolver.resolve(RAW_TOKEN))
                .thenReturn(Optional.of(new AuthenticatedPrincipal(USER_ID, TENANT_ID, "ROLE_USER")));
        UUID sessionId = UUID.randomUUID();
        when(quizQueryService.current(BOARD_ID, USER_ID, TENANT_ID)).thenReturn(new QuizSessionResponse(
                sessionId.toString(), BOARD_ID.toString(), "ACTIVE", 0, null, List.of(),
                "2026-07-21T10:00:00Z", null));

        mockMvc.perform(get(currentPath())
                        .header("Authorization", "Bearer " + RAW_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sessionId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.boardId").value(BOARD_ID.toString()));

        verify(quizQueryService).current(BOARD_ID, USER_ID, TENANT_ID);
    }

    @Test
    void current_noActiveSession_returnsEmptyBody200() throws Exception {
        when(principalResolver.resolve(RAW_TOKEN))
                .thenReturn(Optional.of(new AuthenticatedPrincipal(USER_ID, TENANT_ID, "ROLE_USER")));
        when(quizQueryService.current(BOARD_ID, USER_ID, TENANT_ID)).thenReturn(null);

        mockMvc.perform(get(currentPath())
                        .header("Authorization", "Bearer " + RAW_TOKEN))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body).isBlank();
                });
    }

    @Test
    void current_serviceThrowsBoardNotFound_returns404NotFound() throws Exception {
        when(principalResolver.resolve(RAW_TOKEN))
                .thenReturn(Optional.of(new AuthenticatedPrincipal(USER_ID, TENANT_ID, "ROLE_USER")));
        when(quizQueryService.current(BOARD_ID, USER_ID, TENANT_ID)).thenThrow(new BoardNotFoundException(BOARD_ID));

        mockMvc.perform(get(currentPath())
                        .header("Authorization", "Bearer " + RAW_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Board not found"));
    }

    @Test
    void current_missingAuthorizationHeader_returns401() throws Exception {
        mockMvc.perform(get(currentPath()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void last_validToken_returnsClosedSessionAsJson() throws Exception {
        when(principalResolver.resolve(RAW_TOKEN))
                .thenReturn(Optional.of(new AuthenticatedPrincipal(USER_ID, TENANT_ID, "ROLE_USER")));
        UUID sessionId = UUID.randomUUID();
        when(quizQueryService.last(BOARD_ID, USER_ID, TENANT_ID)).thenReturn(new QuizSessionResponse(
                sessionId.toString(), BOARD_ID.toString(), "CLOSED", 2, null, List.of(),
                "2026-07-21T10:00:00Z", "2026-07-21T10:05:00Z"));

        mockMvc.perform(get(lastPath())
                        .header("Authorization", "Bearer " + RAW_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").value("2026-07-21T10:05:00Z"));

        verify(quizQueryService).last(eq(BOARD_ID), eq(USER_ID), eq(TENANT_ID));
    }

    @Test
    void last_serviceThrowsBoardNotFound_returns404NotFound() throws Exception {
        when(principalResolver.resolve(RAW_TOKEN))
                .thenReturn(Optional.of(new AuthenticatedPrincipal(USER_ID, TENANT_ID, "ROLE_USER")));
        when(quizQueryService.last(BOARD_ID, USER_ID, TENANT_ID)).thenThrow(new BoardNotFoundException(BOARD_ID));

        mockMvc.perform(get(lastPath())
                        .header("Authorization", "Bearer " + RAW_TOKEN))
                .andExpect(status().isNotFound());
    }

    private static String currentPath() {
        return "/collaboratif/whiteboard/boards/" + BOARD_ID + "/quiz/current";
    }

    private static String lastPath() {
        return "/collaboratif/whiteboard/boards/" + BOARD_ID + "/quiz/last";
    }
}
