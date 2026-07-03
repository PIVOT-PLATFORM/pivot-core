package fr.pivot.auth.controller;

import fr.pivot.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the password robustness policy (US01.2.4).
 *
 * <p>Full Spring context + real PostgreSQL (Testcontainers). Weak-password requests are
 * rejected by Bean Validation ({@code @Valid} → 400) <em>before</em> any service call,
 * so these tests do not require Redis (rate limiting is never reached).
 *
 * <p>Traceability:
 * <ul>
 *   <li>AC "Validation backend min 12/1/1/1 appliquée à l'inscription et au reset" —
 *       {@code ac0124_01_*}</li>
 *   <li>AC "endpoint GET /api/auth/password-policy expose les règles" — {@code ac0124_03_*}</li>
 * </ul>
 */
class PasswordPolicyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        // MockMvc construit sur le contexte réel (Bean Validation Spring-aware, filtres inclus
        // via le contexte). @AutoConfigureMockMvc n'est pas utilisable : le module
        // spring-boot-webmvc-test-autoconfigure n'est pas sur le classpath (Spring Boot 4.x).
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void ac0124_03_passwordPolicyEndpoint_exposesConfiguredRules() throws Exception {
        mockMvc.perform(get("/auth/password-policy"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.minLength").value(12))
            .andExpect(jsonPath("$.minUppercase").value(1))
            .andExpect(jsonPath("$.minDigits").value(1))
            .andExpect(jsonPath("$.minSpecial").value(1));
    }

    @Test
    void ac0124_01_register_rejectsPasswordTooShort_with400() throws Exception {
        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"weak@pivot.test","password":"Short1!","firstName":"A","lastName":"B"}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void ac0124_01_register_rejectsPasswordWithoutUppercase_with400() throws Exception {
        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"weak@pivot.test","password":"abcdefghij1!","firstName":"A","lastName":"B"}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void ac0124_01_register_rejectsPasswordWithoutDigit_with400() throws Exception {
        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"weak@pivot.test","password":"Abcdefghijk!","firstName":"A","lastName":"B"}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void ac0124_01_register_rejectsPasswordWithoutSpecial_with400() throws Exception {
        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"weak@pivot.test","password":"Abcdefghijk1","firstName":"A","lastName":"B"}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void ac0124_01_resetPassword_rejectsWeakNewPassword_with400() throws Exception {
        // Weak password is rejected by @Valid before the token is even checked
        mockMvc.perform(post("/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"token":"any-token","newPassword":"weakpass"}
                    """))
            .andExpect(status().isBadRequest());
    }
}
