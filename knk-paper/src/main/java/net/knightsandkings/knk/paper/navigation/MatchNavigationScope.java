package net.knightsandkings.knk.paper.navigation;

import java.util.List;
import java.util.Optional;

import org.bukkit.entity.Player;

/**
 * What a player in a minigame match may navigate to (KNG-234, developer decision 2026-10-22: during a match only the
 * destinations relevant to the match are allowed and offered). A seam: a siege provides its objectives and team
 * spawn ({@link NavigationCompassService}); another minigame can provide its own. Main thread.
 */
public interface MatchNavigationScope {

    /** One destination of a match: a stable key, how it is called, what to tell about it, where it leads. */
    record MatchDestination(String key, String label, String detail, Destination destination) {
    }

    /**
     * The destinations of the player's running match, in the order they are offered; empty when the player is in no
     * match (everything may be navigated to). A present but empty list is a match without destinations: nothing may
     * be navigated to.
     */
    Optional<List<MatchDestination>> destinationsOf(Player player);

    /** Whether {@code destination} is one of the match's destinations for this player (true outside a match). */
    default boolean allows(Player player, Destination destination) {
        Optional<List<MatchDestination>> scope = destinationsOf(player);
        return scope.isEmpty() || scope.get().stream().anyMatch(d -> d.destination().sameAs(destination));
    }
}
