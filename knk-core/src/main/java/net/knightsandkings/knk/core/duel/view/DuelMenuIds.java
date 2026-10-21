package net.knightsandkings.knk.core.duel.view;

/**
 * The ids the duel menu templates (knk-web-api {@code MenuTemplateSeed.Duel.cs}) and the plugin's
 * {@code DuelMenuFeature} share (arena duels DESIGN §9.2–§9.3, plan Phase 6). The seed ↔ plugin contract test
 * checks them against the exported templates.
 */
public final class DuelMenuIds {

    /** {@code /duels}: live matches of every arena. */
    public static final String MENU_MATCHES = "duel_matches";
    /** One match ({@code ctx.token}). */
    public static final String MENU_MATCH_DETAIL = "duel_match_detail";
    /** {@code /arena}: one arena ({@code ctx.arena}, its id; empty = the viewer's arena, else the first one). */
    public static final String MENU_ARENA = "arena_main";
    /** The arena menu's "Challenge a player" picker ({@code ctx.arena}). */
    public static final String MENU_CHALLENGE = "duel_challenge";

    public static final String CTX_TOKEN = "token";
    public static final String CTX_ARENA = "arena";

    /** Root: the match of {@code ctx.token} ({@link DuelMatchDetailView}). */
    public static final String ROOT_MATCH = "duelMatch";
    /** Root: the arena of {@code ctx.arena} ({@link ArenaMenuView}). */
    public static final String ROOT_ARENA = "arena";
    /** Root: every arena's live-match count ({@link DuelServerView}). */
    public static final String ROOT_SERVER = "duels";

    /** Rows: two per live match, challenger then opponent ({@link DuelMatchRowView}); param {@code arenaId} filters. */
    public static final String SOURCE_MATCHES = "duel.matches";
    /** Rows: one side's card with their stats ({@link DuelSideCardView}); params {@code token}, {@code side}. */
    public static final String SOURCE_MATCH_SIDE = "duel.match-side";
    /** Rows: one side's kit, coins and stake items ({@link DuelStakeRowView}); params {@code token}, {@code side}. */
    public static final String SOURCE_STAKES = "duel.stakes";
    /** Rows: online players the viewer could challenge ({@link DuelChallengeRowView}); param {@code arenaId}. */
    public static final String SOURCE_CHALLENGE = "duel.challenge-candidates";

    /** Starts the spectator area's domain warp; param {@code arenaId}. */
    public static final String ACTION_SPECTATE = "duel.spectate";
    /** Closes the menu and runs {@code /duel <name>} (P5's proposal editor); param {@code name}. */
    public static final String ACTION_CHALLENGE = "duel.challenge";
    /** {@code /duel claim}: hands over pending winnings and returned items. */
    public static final String ACTION_CLAIM = "duel.claim";

    /** Render: no live match (param {@code arenaId} narrows it to one arena). */
    public static final String CONDITION_MATCHES_EMPTY = "duel.matches-empty";
    /** Click: the viewer stands inside the arena's region (param {@code arenaId}). */
    public static final String CONDITION_IN_ARENA = "duel.in-arena";

    public static final String SIDE_CHALLENGER = "CHALLENGER";
    public static final String SIDE_OPPONENT = "OPPONENT";

    private DuelMenuIds() {
    }
}
