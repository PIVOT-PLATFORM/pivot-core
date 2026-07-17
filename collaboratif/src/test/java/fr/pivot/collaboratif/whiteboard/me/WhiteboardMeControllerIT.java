package fr.pivot.collaboratif.whiteboard.me;

import fr.pivot.collaboratif.AbstractCollaboratifIntegrationTest;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code GET /whiteboard/me} (US08.12.2 enabler).
 *
 * <p>Verifies the endpoint returns the authenticated caller's own {@code public.users.id},
 * resolved from the bearer token, and rejects unauthenticated calls — the identity the dot-vote
 * UI relies on to attribute votes to the current user.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WhiteboardMeControllerIT extends AbstractCollaboratifIntegrationTest {

    private static final String ME_PATH = "/collaboratif/whiteboard/me";

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;
    private AuthFixture user;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        user = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    void returns_the_authenticated_caller_own_user_id() throws Exception {
        mockMvc.perform(get(ME_PATH).header("Authorization", "Bearer " + user.rawToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(String.valueOf(user.userId())));
    }

    @Test
    void rejects_an_unauthenticated_call_with_401() throws Exception {
        mockMvc.perform(get(ME_PATH))
                .andExpect(status().isUnauthorized());
    }
}
