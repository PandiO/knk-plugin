package net.knightsandkings.knk.core.siege.menu;

/**
 * Siege Phase 8b: every menu key, variable root, content source, action and condition id the siege
 * menus use (DESIGN §10.2, MENU_TEMPLATES.md Part C). knk-web-api's {@code MenuTemplateSeed.Siege.cs}
 * references the same strings; the seed ↔ plugin contract test (knk-api-client) checks them.
 */
public final class SiegeMenuIds {
    private SiegeMenuIds() {}

    // ---- menus ----
    public static final String MENU_OVERVIEW = "siege.overview";
    public static final String MENU_INFORMATION = "siege.information";
    public static final String MENU_SPAWNPOINT = "siege.spawnpoint";
    /** Prefix for event-driven refresh ({@code refreshOpenMenus}). */
    public static final String MENU_PREFIX = "siege.";

    /** Context parameter of {@link #MENU_INFORMATION}. */
    public static final String CTX_LOBBY_ID = "lobbyId";

    // ---- variable roots ----
    public static final String ROOT_SIEGE = "siege";
    public static final String ROOT_VIEWER = "siegeViewer";
    public static final String ROOT_SERVER = "siegeServer";

    // ---- content sources ----
    public static final String SOURCE_LOBBIES = "siege.lobbies";
    public static final String SOURCE_VOTE_CANDIDATES = "siege.vote-candidates";
    public static final String SOURCE_BODY = "siege.body";
    public static final String SOURCE_SPAWN_OPTIONS = "siege.spawn-options";

    // ---- actions ----
    public static final String ACTION_JOIN = "siege.join";
    public static final String ACTION_LEAVE = "siege.leave";
    public static final String ACTION_VOTE = "siege.vote";
    public static final String ACTION_VOTE_RANDOM = "siege.vote.random";
    public static final String ACTION_SPAWN = "siege.spawn";
    public static final String ACTION_OPEN_OWN = "siege.open-own";

    // ---- conditions ----
    public static final String CONDITION_PHASE = "siege.phase";
    public static final String CONDITION_PARTICIPATING = "siege.participating";
    public static final String CONDITION_JOIN_ELIGIBLE = "siege.join-eligible";
    public static final String CONDITION_VOTE_OPEN = "siege.vote-open";
    public static final String CONDITION_SPAWN_AVAILABLE = "siege.spawn-available";
    public static final String CONDITION_LOBBIES_EMPTY = "siege.lobbies-empty";

    /** The {@code scenarioId} param value of {@link #ACTION_VOTE} for the Random option. */
    public static final String VOTE_RANDOM = "random";
}
