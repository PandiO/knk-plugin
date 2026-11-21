package net.knightsandkings.knk.paper.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.menu.opener.MenuItemSituations;
import net.knightsandkings.knk.core.siege.SiegePhase;

/** Siege shop DESIGN § 4.3.3 (PandiO/knk-workspace#871): PvP Siege's menu item situations. */
class SiegeMenuItemSituationsTest {

    private final UUID player = UUID.randomUUID();
    private final Set<UUID> inMatch = new HashSet<>();
    private final Map<UUID, SiegePhase> phases = new HashMap<>();
    private final MenuItemSituations situations = new MenuItemSituations();

    private Optional<String> situation() {
        return situations.situationOf(player);
    }

    @Test
    void aRunningMatchIsSiegeMatch_TheHubAndTheResultAreSiegeHub_AnythingElseIsNone() {
        SiegeMenuItemSituations.register(situations, inMatch::contains, id -> Optional.ofNullable(phases.get(id)));

        assertEquals(Optional.empty(), situation());
        phases.put(player, SiegePhase.MATCHMAKING);
        assertEquals(Optional.empty(), situation());
        phases.put(player, SiegePhase.HUB);
        assertEquals(Optional.of(MenuItemSituations.SIEGE_HUB), situation());
        phases.put(player, SiegePhase.IN_PROGRESS);
        inMatch.add(player);
        assertEquals(Optional.of(MenuItemSituations.SIEGE_MATCH), situation());
        inMatch.remove(player);
        phases.put(player, SiegePhase.POST_MATCH);
        assertEquals(Optional.of(MenuItemSituations.SIEGE_HUB), situation());
    }

    @Test
    void siegeSurvivalMatchIsLeftForSiegeSurvival() {
        SiegeMenuItemSituations.register(situations, id -> true, id -> Optional.empty());
        assertEquals(false, situations.isProvided(MenuItemSituations.SIEGE_SURVIVAL_MATCH));
    }
}
