package net.knightsandkings.knk.core.navigation.destinations;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import net.knightsandkings.knk.core.domain.users.ActiveMode;

/**
 * Immutable snapshot of one player at evaluation time (destination engine DESIGN §3): what the scopes ask about.
 * Built on the main thread from the offline-aware sources (§7.5: active mode, freeze and permission answers come
 * from the offline security store; loading state is local; siege membership is in memory), so building one never
 * blocks on the API. Cheap: a handful of fields.
 *
 * @param player       the player
 * @param world        the world the player is in (destinations of other worlds are hidden from completion, E11)
 * @param mode         owner/staff mode ({@link ActiveMode#NONE} otherwise); exempt from the policies except freeze (E6, E9)
 * @param loading      the account is still loading: the mode is not known yet, so nothing can be exempt
 * @param frozen       admin-frozen
 * @param permissions  the permission nodes the player holds, among those the scopes ask for ({@code knk.navigate},
 *                     {@code knk.navigate.any}); a node not in the set is one the player lacks
 * @param siege        the siege facts ({@link Siege#NONE} outside a siege)
 * @param modelVersion the version of the in-memory model the list is built from; carried by the handle (§6)
 */
public record NavigationContext(UUID player, String world, ActiveMode mode, boolean loading, boolean frozen,
                                Set<String> permissions, Siege siege, long modelVersion) {

    /**
     * The minigame facts of a siege member (DESIGN §3: "in a running siege match, team T, scenario S").
     *
     * @param lobbyMember    member of a siege lobby (before, during or after its match)
     * @param matchRunning   that lobby's match is running now (implies {@code lobbyMember})
     * @param team           the member's team id, when in a match and known; null otherwise
     * @param scenario       the scenario id of the running match, when known; null otherwise
     * @param compassEnabled the compass setting {@code siegeEnabled}: navigation in sieges is switched on
     */
    public record Siege(boolean lobbyMember, boolean matchRunning, Integer team, Integer scenario, boolean compassEnabled) {

        /** Not in a siege. */
        public static final Siege NONE = new Siege(false, false, null, null, true);

        public Siege {
            lobbyMember = lobbyMember || matchRunning;
        }

        /** A lobby member whose match has not started (or is over). */
        public static Siege lobby() {
            return new Siege(true, false, null, null, true);
        }

        /** A member of a running match. */
        public static Siege match(int team, int scenario) {
            return new Siege(true, true, team, scenario, true);
        }

        public Siege withCompassEnabled(boolean enabled) {
            return new Siege(lobbyMember, matchRunning, team, scenario, enabled);
        }
    }

    public NavigationContext {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(world, "world");
        mode = mode == null ? ActiveMode.NONE : mode;
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        siege = siege == null ? Siege.NONE : siege;
    }

    /** A plain player in {@code world}: no mode, loaded, not frozen, no permissions, not in a siege, model version 0. */
    public static NavigationContext of(UUID player, String world) {
        return new NavigationContext(player, world, ActiveMode.NONE, false, false, Set.of(), Siege.NONE, 0);
    }

    /** Whether the player holds {@code node}. */
    public boolean hasPermission(String node) {
        return permissions.contains(node);
    }

    public NavigationContext withMode(ActiveMode mode) {
        return new NavigationContext(player, world, mode, loading, frozen, permissions, siege, modelVersion);
    }

    public NavigationContext withLoading(boolean loading) {
        return new NavigationContext(player, world, mode, loading, frozen, permissions, siege, modelVersion);
    }

    public NavigationContext withFrozen(boolean frozen) {
        return new NavigationContext(player, world, mode, loading, frozen, permissions, siege, modelVersion);
    }

    /** The same context with {@code nodes} held as well. */
    public NavigationContext withPermissions(String... nodes) {
        Set<String> all = new LinkedHashSet<>(permissions);
        all.addAll(Arrays.asList(nodes));
        return new NavigationContext(player, world, mode, loading, frozen, all, siege, modelVersion);
    }

    public NavigationContext withSiege(Siege siege) {
        return new NavigationContext(player, world, mode, loading, frozen, permissions, siege, modelVersion);
    }

    public NavigationContext withWorld(String world) {
        return new NavigationContext(player, world, mode, loading, frozen, permissions, siege, modelVersion);
    }

    public NavigationContext withModelVersion(long modelVersion) {
        return new NavigationContext(player, world, mode, loading, frozen, permissions, siege, modelVersion);
    }
}
