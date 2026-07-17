package fr.pivot.collaboratif.whiteboard.importer;

import fr.pivot.collaboratif.AbstractCollaboratifIntegrationTest;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the US08.13.1 50&nbsp;MB body-size AC on
 * {@code POST /whiteboard/boards/{boardId}/import/klaxoon}: "corps d'import dépassant 50 Mo → 413
 * avant tout traitement".
 *
 * <p>Deliberately not a {@code MockMvc} test: {@code MockMvcBuilders.webAppContextSetup(...)}
 * dispatches straight to the {@code DispatcherServlet}, bypassing the real servlet filter chain —
 * {@link ImportBodySizeLimitFilter} would never run, so the 413 behaviour would go unverified. This
 * class instead drives a real embedded Tomcat instance over a real HTTP client
 * ({@link HttpClient}), exactly like the existing STOMP transport-level tests in this module
 * ({@code WhiteboardOversizedDrawPayloadIT}, {@code WhiteboardRateLimitEnforcementIT}).
 *
 * <p>The filter rejects on {@code Content-Length} alone before reading a single body byte, so no
 * board needs to exist and no valid role is required for this specific assertion — the guard is
 * unconditional and runs ahead of any business logic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WhiteboardImportBodySizeLimitIT extends AbstractCollaboratifIntegrationTest {

    @LocalServerPort
    private int port;

    /**
     * Given a request body over 50 MB, when it is posted to the Klaxoon import endpoint, then the
     * server responds 413 without ever reaching the controller/service layer.
     */
    @Test
    void oversizedImportBody_returns413() throws Exception {
        AuthFixture fixture = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

        // 50 * 1024 * 1024 + 1 bytes of raw content — JSON validity is irrelevant here, the
        // filter rejects purely on size (via the Content-Length header), before any parsing.
        byte[] oversizedBody = new byte[(50 * 1024 * 1024) + 1];
        java.util.Arrays.fill(oversizedBody, (byte) 'a');

        String url = "http://localhost:" + port
                + "/api/collaboratif/whiteboard/boards/" + UUID.randomUUID() + "/import/klaxoon";
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", fixture.authorizationHeader())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(oversizedBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
    }
}
