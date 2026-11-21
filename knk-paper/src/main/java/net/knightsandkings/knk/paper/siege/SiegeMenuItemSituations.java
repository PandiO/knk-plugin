package net.knightsandkings.knk.paper.siege;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import net.knightsandkings.knk.core.menu.opener.MenuItemSituations;
import net.knightsandkings.knk.core.siege.SiegePhase;

/**
 * PvP Siege's menu item situations (siege shop L6, PandiO/knk-workspace#871, DESIGN § 4.3.3, § 5.7.1), answered from
 * the runtime's memory on every click:
 * <ul>
 *   <li>{@link MenuItemSituations#SIEGE_MATCH}: a member of a running match ({@link SiegeService#runningMatchOf}).</li>
 *   <li>{@link MenuItemSituations#SIEGE_HUB}: a member of a round at its scenario hub: the team split before the
 *       match ({@link SiegePhase#HUB}) or the result after it ({@link SiegePhase#POST_MATCH}).</li>
 * </ul>
 * {@link MenuItemSituations#SIEGE_SURVIVAL_MATCH} is Siege Survival's (PandiO/knk-workspace#41) to provide at its merge.
 */
public final class SiegeMenuItemSituations {

    private SiegeMenuItemSituations() {
    }

    public static void register(MenuItemSituations situations, SiegeService siege) {
        register(situations, id -> siege.runningMatchOf(id).isPresent(),
            id -> siege.lobbyOf(id).map(SiegeLobbyRuntime::phase));
    }

    /** Tests: the runtime's answers as functions. */
    static void register(MenuItemSituations situations, java.util.function.Predicate<UUID> inRunningMatch,
                         Function<UUID, Optional<SiegePhase>> lobbyPhase) {
        situations.provide(MenuItemSituations.SIEGE_MATCH, inRunningMatch::test);
        situations.provide(MenuItemSituations.SIEGE_HUB, id -> lobbyPhase.apply(id)
            .map(phase -> phase == SiegePhase.HUB || phase == SiegePhase.POST_MATCH).orElse(false));
    }
}
