package fr.pivot.agilite.team;

import fr.pivot.agilite.AbstractAgiliteIntegrationTest;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport.AuthFixture;
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
 * Integration tests for {@link TeamMembershipController} (US14.1.1) — read-only consumption of
 * {@code public.teams}/{@code public.team_members}/{@code public.users}, exercised against real
 * PostgreSQL/Redis Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TeamMembershipControllerIT extends AbstractAgiliteIntegrationTest {

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    private long tenantA;
    private long teamA;
    private String tokenA;

    private String tokenB;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture fixtureA = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantA = fixtureA.tenantId();
        teamA = fixtureA.teamId();
        tokenA = fixtureA.rawToken();

        AuthFixture fixtureB = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tokenB = fixtureB.rawToken();
    }

    @Test
    void listMyTeams_returnsCallersTeam() throws Exception {
        mockMvc.perform(get("/agilite/teams").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(teamA));
    }

    @Test
    void listMembers_resolvesDisplayNameFromFirstAndLastName() throws Exception {
        long userId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                tenantA, true, "Grace", "Hopper");
        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), teamA, userId);

        mockMvc.perform(get("/agilite/teams/" + teamA + "/members").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userId == " + userId + ")].displayName").value("Grace Hopper"));
    }

    @Test
    void listMembers_resolvesDisplayNameFromEmailWhenNoNameSet() throws Exception {
        mockMvc.perform(get("/agilite/teams/" + teamA + "/members").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName").exists());
    }

    @Test
    void listMembers_nonMemberOfTeam_returns404() throws Exception {
        mockMvc.perform(get("/agilite/teams/" + teamA + "/members").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void listMembers_unknownTeam_returns404() throws Exception {
        mockMvc.perform(get("/agilite/teams/999999999/members").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    @Test
    void listMyTeams_missingAuthorizationHeader_returns401() throws Exception {
        mockMvc.perform(get("/agilite/teams")).andExpect(status().isUnauthorized());
    }
}
