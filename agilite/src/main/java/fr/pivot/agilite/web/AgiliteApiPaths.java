package fr.pivot.agilite.web;

/**
 * Shared REST route-prefix constant for every HTTP controller of this module (EN53.1 Vague 1
 * modulith integration).
 *
 * <p><strong>Why this exists.</strong> This module was originally a standalone Spring Boot
 * application ({@code pivot-agilite-core}) deployed behind its own {@code
 * server.servlet.context-path: /api/agilite} (see the former {@code application.yml}). Every
 * controller therefore mapped a bare, un-prefixed relative path — {@code @RequestMapping("/poker/rooms")},
 * {@code ("/wheels")}, {@code ("/teams")}, {@code ("/retro/sessions")}, etc. — and relied
 * entirely on that dedicated context-path to produce the {@code /api/agilite/...} contract the
 * Angular frontend (the {@code AGILITE_API_URL} token) is built against.
 *
 * <p>In the modulith ({@code pivot-core-app}), every module shares a single global {@code
 * server.servlet.context-path: /api} (see the app's own {@code application.yml}) — there is no
 * per-module context-path any more. Without this prefix, this module's controllers would
 * resolve to plain {@code /api/poker/rooms}, {@code /api/wheels}, {@code /api/teams}, {@code
 * /api/retro/sessions}, breaking the frontend contract and, worse, risking a silent route
 * collision with another domain module also owning a generic segment (e.g. {@code /teams}).
 *
 * <p>Every controller's class-level {@code @RequestMapping} in this module is composed as
 * {@code AgiliteApiPaths.BASE + "/<resource>"} so the aggregated app now serves {@code
 * /api/agilite/poker/rooms}, {@code /api/agilite/wheels}, {@code /api/agilite/teams}, {@code
 * /api/agilite/retro/sessions}, etc. — restoring the exact contract the frontend already
 * expects, this time without depending on any per-module deployment-time configuration.
 *
 * <p><strong>Not applied to WebSocket/STOMP {@code @MessageMapping} destinations</strong>
 * ({@code PokerVoteWsController}, {@code RetroVoteWsController}, {@code RetroCardWsController})
 * — those live in the STOMP application-destination namespace configured by {@code
 * WebSocketConfig}'s message broker, a separate addressing space from the servlet {@code
 * context-path}/{@code @RequestMapping} routing this constant governs, and already carry their
 * own {@code /poker}/{@code /retro} prefixes via {@code PokerRoomDestinations}/{@code
 * RetroSessionDestinations}/{@code WheelDestinations}.
 */
public final class AgiliteApiPaths {

    /**
     * The single segment every REST controller of this module prefixes its own resource path
     * with, so the aggregated app's global {@code /api} context-path yields {@code /api/agilite/...}.
     */
    public static final String BASE = "/agilite";

    private AgiliteApiPaths() {
    }
}
