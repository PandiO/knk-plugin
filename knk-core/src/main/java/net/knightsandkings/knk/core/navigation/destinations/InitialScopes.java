package net.knightsandkings.knk.core.navigation.destinations;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.knightsandkings.knk.core.domain.users.ActiveMode;

/**
 * The initial scope table (destination engine DESIGN §4.1), highest priority first. The reasons are today's
 * player-facing texts ({@code NavigationMessages}), so players see the same words. Freeze sits above the bypass
 * (E9): the one rule that holds for owner/staff mode and {@code knk.navigate.any} holders too. The providers are
 * given, not built here: the catalogue is phase 2, the siege match provider phase 3 (DESIGN §10).
 *
 * <pre>
 * 110 account loading   -> empty, "still loading"
 * 110 frozen            -> empty, "while frozen" (also in owner/staff mode)
 * 100 bypass            -> every provider (owner/staff mode, or knk.navigate.any)
 *  80 no permission     -> empty, "no permission" (lacks knk.navigate)
 *  60 siege match       -> the match provider (running match, compass on for sieges)
 *  55 siege match, off  -> empty, "switched off in this siege"
 *  50 siege lobby       -> empty, "in a siege" - unless a hub provider has destinations (E10; none today)
 *  10 world             -> the world providers
 * </pre>
 */
public final class InitialScopes {

    /** The command's node (default true). */
    public static final String NAVIGATE_NODE = "knk.navigate";
    /** The bypass node (E5, E12): every destination, no further policy; not granted by default. */
    public static final String BYPASS_NODE = "knk.navigate.any";

    public static final String ACCOUNT_LOADING = "account-loading";
    public static final String FROZEN = "frozen";
    public static final String BYPASS = "bypass";
    public static final String NO_PERMISSION = "no-permission";
    public static final String SIEGE_MATCH = "siege-match";
    public static final String SIEGE_MATCH_COMPASS_OFF = "siege-match-compass-off";
    public static final String SIEGE_LOBBY = "siege-lobby";
    public static final String WORLD = "world";

    public static final String LOADING_REASON = "Your account is still loading - try again in a moment.";
    public static final String FROZEN_REASON = "You can't navigate while frozen.";
    public static final String NO_PERMISSION_REASON = "You don't have permission to use /navigate.";
    public static final String COMPASS_OFF_REASON = "Navigation is switched off in this siege.";
    public static final String IN_SIEGE_REASON = "You can't navigate while you are in a siege.";

    private InitialScopes() {
    }

    /** The table without a hub provider (today: the hub world has no road network, E10). */
    public static List<Scope> create(List<Provider> world, Provider siegeMatch) {
        return create(world, siegeMatch, List.of());
    }

    /**
     * @param world      the providers of the world catalogue (domains, Locations, streets, nodes; the compass's
     *                   configured targets)
     * @param siegeMatch the running match's objectives and team spawn
     * @param hub        what a lobby member may navigate to while no match runs; empty for none
     */
    public static List<Scope> create(List<Provider> world, Provider siegeMatch, List<Provider> hub) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(siegeMatch, "siegeMatch");
        Objects.requireNonNull(hub, "hub");
        List<Provider> everything = new ArrayList<>(world);
        everything.add(siegeMatch);
        return List.of(
            Scope.empty(ACCOUNT_LOADING, 110, "the world", NavigationContext::loading, LOADING_REASON),
            Scope.empty(FROZEN, 110, "the world", NavigationContext::frozen, FROZEN_REASON),
            Scope.listing(BYPASS, 100, "the world", InitialScopes::bypasses, everything),
            Scope.empty(NO_PERMISSION, 80, "the world", ctx -> !ctx.hasPermission(NAVIGATE_NODE), NO_PERMISSION_REASON),
            Scope.listing(SIEGE_MATCH, 60, "this match", ctx -> ctx.siege().matchRunning() && ctx.siege().compassEnabled(),
                List.of(siegeMatch)),
            Scope.empty(SIEGE_MATCH_COMPASS_OFF, 55, "this match",
                ctx -> ctx.siege().matchRunning() && !ctx.siege().compassEnabled(), COMPASS_OFF_REASON),
            Scope.listing(SIEGE_LOBBY, 50, "this siege", ctx -> ctx.siege().lobbyMember() && !ctx.siege().matchRunning(),
                hub, IN_SIEGE_REASON),
            Scope.listing(WORLD, 10, "the world", ctx -> true, world));
    }

    /** Owner or staff mode (E6), or the bypass node (E5). */
    static boolean bypasses(NavigationContext ctx) {
        return ctx.mode() == ActiveMode.OWNER || ctx.mode() == ActiveMode.STAFF || ctx.hasPermission(BYPASS_NODE);
    }
}
