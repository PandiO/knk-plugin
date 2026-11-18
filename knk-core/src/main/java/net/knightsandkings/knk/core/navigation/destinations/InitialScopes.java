package net.knightsandkings.knk.core.navigation.destinations;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.knightsandkings.knk.core.domain.users.ActiveMode;

/**
 * The initial scope table (destination engine DESIGN § 4.1), highest priority first. Each empty scope's reason is a
 * code plus today's {@code /navigate} text (E15), so players see the same words and a client with its own wording
 * (the compass) switches on the code. Freeze sits above the bypass (E9): the one rule that holds for owner/staff mode
 * and {@code knk.navigate.any} holders too. The providers are given, not built here (DESIGN § 10: the catalogue,
 * siege match and compass target providers are phase 3).
 *
 * <pre>
 * 110 account loading   -> empty, loading            "still loading"
 * 110 frozen            -> empty, frozen             "while frozen" (also in owner/staff mode)
 * 100 bypass            -> every provider (owner/staff mode, or knk.navigate.any)
 *  80 no permission     -> empty, no-permission      (lacks knk.navigate)
 *  60 siege match       -> the match provider (running match, compass on); empty: siege-match-empty
 *  55 siege match, off  -> empty, siege-compass-off  "This match has no destinations to navigate to."
 *  50 siege lobby       -> empty, siege-lobby        "in a siege" - unless a hub provider has destinations (E10)
 *  10 world             -> the world providers (catalogue, compass targets, landmarks when on)
 * </pre>
 *
 * <p>The World scope's providers are a list (§ 3.2): the landmark provider of off-road phase 5.1 (#708) is one more
 * entry, registered by the plugin when {@code navigation.landmarks} is on, without a change here. Its candidates have
 * the key {@code landmark:<id>} (the landmark's id, stable across renames), the type word {@code landmark}, the
 * landmark's id for {@code landmark:#<id>}, the detail {@code in <domain>} when linked to a domain and the source
 * {@code landmarks}; the place is a {@link Candidate.Place.Point} with the arrival radius of a {@code Radius} extent
 * (none: within {@code arrive-distance}) or a {@link Candidate.Place.Region} for a {@code Region} extent. Being
 * world providers, landmarks are hidden by every exclusive scope above World and listed by the bypass.
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

    /** Reason codes (E15): stable, what a client with its own wording switches on. */
    public static final String LOADING_CODE = "loading";
    public static final String FROZEN_CODE = "frozen";
    public static final String NO_PERMISSION_CODE = "no-permission";
    public static final String SIEGE_COMPASS_OFF_CODE = "siege-compass-off";
    public static final String SIEGE_MATCH_EMPTY_CODE = "siege-match-empty";
    public static final String SIEGE_LOBBY_CODE = "siege-lobby";

    public static final Reason LOADING_REASON =
        Reason.of(LOADING_CODE, "Your account is still loading - try again in a moment.");
    public static final Reason FROZEN_REASON = Reason.of(FROZEN_CODE, "You can't navigate while frozen.");
    public static final Reason NO_PERMISSION_REASON =
        Reason.of(NO_PERMISSION_CODE, "You don't have permission to use /navigate.");
    /**
     * Scope 55: the compass item is disabled or the compass is off for sieges. Today's text
     * ({@code CompassMessages.noMatchDestinations}, DESIGN § 2.1 item 4); the compass says "The navigation compass is
     * switched off during sieges." for this code.
     */
    public static final Reason COMPASS_OFF_REASON =
        Reason.of(SIEGE_COMPASS_OFF_CODE, "This match has no destinations to navigate to.");
    /** Scope 60 with nothing listed (a member without a team, a board without objectives): today's same text. */
    public static final Reason MATCH_EMPTY_REASON =
        Reason.of(SIEGE_MATCH_EMPTY_CODE, "This match has no destinations to navigate to.");
    /** Scope 50; the compass says "The navigation compass works once the siege match has started." for this code. */
    public static final Reason IN_SIEGE_REASON =
        Reason.of(SIEGE_LOBBY_CODE, "You can't navigate while you are in a siege.");

    private InitialScopes() {
    }

    /** The table without a hub provider (today: the hub world has no road network, E10). */
    public static List<Scope> create(List<Provider> world, Provider siegeMatch) {
        return create(world, siegeMatch, List.of());
    }

    /**
     * @param world      the providers of the world catalogue (domains, Locations, streets, nodes; the compass's
     *                   configured targets; the landmark provider when {@code navigation.landmarks} is on)
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
                List.of(siegeMatch), MATCH_EMPTY_REASON),
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
