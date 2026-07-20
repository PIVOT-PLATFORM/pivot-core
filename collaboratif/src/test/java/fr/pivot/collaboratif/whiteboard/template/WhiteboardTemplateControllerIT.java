package fr.pivot.collaboratif.whiteboard.template;

import fr.pivot.collaboratif.AbstractCollaboratifIntegrationTest;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
import fr.pivot.collaboratif.whiteboard.board.WhiteboardModuleCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link WhiteboardTemplateController} (US08.4.1), exercising the
 * full Spring context against a real PostgreSQL database (including the Flyway-seeded
 * templates — V1 initial 3 plus the V7 re-platform's 7 additional ones) and Redis, both
 * provided by Testcontainers.
 *
 * <p>Note: MockMvc via {@code webAppContextSetup} dispatches against the servlet path
 * directly, without the {@code server.servlet.context-path} prefix — paths used here
 * start with {@code /whiteboard/templates}, not {@code /api/collaboratif/...}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WhiteboardTemplateControllerIT extends AbstractCollaboratifIntegrationTest {

    private static final String BASE_PATH = "/collaboratif/whiteboard/templates";

    /** Every global template code, in the exact {@code display_order} seeded by V1 + V7. */
    private static final List<String> EXPECTED_CODES_IN_ORDER = List.of(
            "BRAINSTORM",
            "RETROSPECTIVE",
            "USER_STORY_MAP",
            "RETRO_START_STOP_CONTINUE",
            "RETRO_MAD_SAD_GLAD",
            "RETRO_4L",
            "RETRO_SPEEDBOAT",
            "RISK_ANALYSIS",
            "MINDMAP",
            "VISUAL_MANAGEMENT");

    @Autowired
    private WebApplicationContext wac;

    @MockitoBean
    private WhiteboardModuleCheck moduleCheck;

    private MockMvc mockMvc;

    private long tenantA;
    private String tokenA;

    /**
     * Sets up MockMvc from the web application context before each test, seeds a real
     * tenant/user/token fixture (A) via {@link PlatformAuthTestSupport} (EN08.3), and stubs
     * the module-activation check to "enabled" by default — individual tests override this
     * to exercise the disabled path.
     */
    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        when(moduleCheck.isEnabled(any())).thenReturn(true);

        AuthFixture fixtureA = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantA = fixtureA.tenantId();
        tokenA = fixtureA.rawToken();
    }

    /**
     * Given the 10 templates seeded via Flyway (V1's original 3, re-authored onto the live
     * board model, plus V7's 7 additional ones), when GET /whiteboard/templates is called,
     * then it returns HTTP 200 with all 10 templates in {@code display_order}, "Vierge"
     * (blank) absent.
     */
    @Test
    void listTemplates_returnsAllTenSeededGlobalTemplatesInOrder() throws Exception {
        var result = mockMvc.perform(get(BASE_PATH)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(EXPECTED_CODES_IN_ORDER.size()))
                .andExpect(jsonPath("$[0].name").value("Brainstorm"))
                .andExpect(jsonPath("$[0].id").isString())
                .andExpect(jsonPath("$[0].thumbnailUrl").isString());

        for (int i = 0; i < EXPECTED_CODES_IN_ORDER.size(); i++) {
            result.andExpect(jsonPath("$[%d].code".formatted(i)).value(EXPECTED_CODES_IN_ORDER.get(i)));
        }
    }

    /**
     * Given the Authorization bearer header is absent,
     * when GET /whiteboard/templates is called, then it returns HTTP 401 Unauthorized.
     */
    @Test
    void listTemplates_missingPrincipalHeaders_returns401() throws Exception {
        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Given the whiteboard module is disabled for the caller's tenant, when
     * GET /whiteboard/templates is called, then it returns HTTP 403 Forbidden —
     * a disabled module must reject the request before any template data is returned.
     */
    @Test
    void listTemplates_moduleDisabledForTenant_returns403() throws Exception {
        when(moduleCheck.isEnabled(tenantA)).thenReturn(false);

        mockMvc.perform(get(BASE_PATH)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isForbidden());
    }
}
