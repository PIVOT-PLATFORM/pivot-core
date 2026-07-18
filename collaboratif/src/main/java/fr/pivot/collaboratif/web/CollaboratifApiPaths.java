package fr.pivot.collaboratif.web;

/**
 * Shared REST route-prefix constant for every HTTP controller of this module, and for the STOMP
 * WebSocket endpoint, (EN53.2 Vague 2 modulith integration — mirrors {@code
 * fr.pivot.agilite.web.AgiliteApiPaths} from the EN53.1 Vague 1 pass).
 *
 * <p><strong>Why this exists.</strong> This module was originally a standalone Spring Boot
 * application ({@code pivot-collaboratif-core}) deployed behind its own {@code
 * server.servlet.context-path: /api/collaboratif} (see the former {@code application.yml}). Every
 * controller therefore mapped a bare, un-prefixed relative path — {@code
 * @RequestMapping("/whiteboard/boards")}, {@code ("/whiteboard/join")}, {@code ("/whiteboard/me")},
 * {@code ("/whiteboard/templates")}, etc. — and the WebSocket endpoint was registered at the bare
 * {@code /ws/whiteboard}, relying entirely on that dedicated context-path to produce the {@code
 * /api/collaboratif/...} contract {@code pivot-collaboratif-ui} is built against.
 *
 * <p>In the modulith ({@code pivot-core-app}), every module shares a single global {@code
 * server.servlet.context-path: /api} (see the app's own {@code application.yml}) — there is no
 * per-module context-path any more. Without this prefix, this module's controllers would resolve
 * to plain {@code /api/whiteboard/boards}, {@code /api/whiteboard/join}, etc. — breaking the
 * frontend contract and, worse, leaving a bare, generic, high-collision-risk {@code /whiteboard/*}
 * segment with no module namespacing at all (flagged as an unresolved routing-collision risk in
 * this module's own {@code src/test/resources/application-test.yml} ahead of this pass).
 *
 * <p>Every controller's class-level {@code @RequestMapping} in this module is composed as
 * {@code CollaboratifApiPaths.BASE + "/<resource>"} so the aggregated app now serves {@code
 * /api/collaboratif/whiteboard/boards}, {@code /api/collaboratif/whiteboard/join}, {@code
 * /api/collaboratif/whiteboard/me}, {@code /api/collaboratif/whiteboard/templates}, etc. —
 * restoring the exact contract the frontend already expects, this time without depending on any
 * per-module deployment-time configuration. The STOMP WebSocket endpoint ({@link
 * fr.pivot.collaboratif.config.CollaboratifWebSocketConfig#registerStompEndpoints}) is prefixed
 * the same way, so its final path stays {@code /api/collaboratif/ws/whiteboard}.
 *
 * <p><strong>Not applied to STOMP {@code @MessageMapping} destinations</strong> ({@code
 * WhiteboardActionController}'s {@code /whiteboard/{boardId}/action}) — those live in the STOMP
 * application-destination namespace configured by {@code CollaboratifWebSocketConfig}'s message
 * broker ({@code setApplicationDestinationPrefixes("/app")}), a separate addressing space from the
 * servlet {@code context-path}/{@code @RequestMapping} routing this constant governs. Unlike
 * agilite (which needed its own {@code /app/agilite} application-destination prefix), this
 * module's client-to-server destinations already carry their own {@code /whiteboard} segment
 * (e.g. {@code /app/whiteboard/{boardId}/action}), so no additional {@code /app/collaboratif}
 * scoping is needed there — see {@code CollaboratifWebSocketConfig}'s class JavaDoc.
 */
public final class CollaboratifApiPaths {

    /**
     * The single segment every REST controller of this module (and its STOMP WebSocket endpoint)
     * prefixes its own resource path with, so the aggregated app's global {@code /api}
     * context-path yields {@code /api/collaboratif/...}.
     */
    public static final String BASE = "/collaboratif";

    private CollaboratifApiPaths() {
    }
}
