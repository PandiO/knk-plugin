package net.knightsandkings.knk.core.telemetry;

import java.util.Set;

/**
 * The diagnostic events the plugin emits (KNG-34 link 6, DESIGN.md §F.12), mirroring knk-web-api's
 * {@code TelemetryEventCatalog} (which allowlists each name's payload keys). The level is fixed per
 * name: enhanced events are only emitted for owner-picked players or test runs.
 */
public final class TelemetryEventNames {

    public static final String SESSION_JOIN = "session.join";
    public static final String SESSION_LEAVE = "session.leave";
    public static final String SESSION_AFK_CHANGED = "session.afk_changed";
    public static final String MENU_OPENED = "menu.opened";
    public static final String MENU_ACTION = "menu.action";
    public static final String COMMAND_RESULT = "command.result";
    public static final String SIEGE_LOBBY_JOIN_ATTEMPT = "siege.lobby_join_attempt";
    public static final String SIEGE_VOTE_CAST = "siege.vote_cast";
    public static final String SIEGE_TEAM_ASSIGNMENT = "siege.team_assignment";
    public static final String SIEGE_MATCH_JOIN = "siege.match_join";
    public static final String SIEGE_MATCH_LEAVE = "siege.match_leave";
    public static final String SIEGE_MATCH_PHASE = "siege.match_phase";
    public static final String SIEGE_OBJECTIVE_CAPTURED = "siege.objective_captured";
    public static final String SIEGE_GATE_DESTROYED = "siege.gate_destroyed";
    public static final String DISCOVERY_GRANTED = "discovery.granted";
    public static final String API_CALL_FAILED = "api.call_failed";
    public static final String TELEMETRY_DROPPED = "telemetry.dropped";

    public static final String MOVEMENT_SAMPLE = "movement.sample";
    public static final String MENU_CLICK = "menu.click";
    public static final String COMBAT_HIT = "combat.hit";
    public static final String GATE_HIT = "gate.hit";

    public static final Set<String> ENHANCED = Set.of(MOVEMENT_SAMPLE, MENU_CLICK, COMBAT_HIT, GATE_HIT);

    private TelemetryEventNames() {
    }

    public static TelemetryEvent.Level levelOf(String name) {
        return ENHANCED.contains(name) ? TelemetryEvent.Level.ENHANCED : TelemetryEvent.Level.BASELINE;
    }
}
