package net.knightsandkings.knk.core.menu.opener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Siege shop DESIGN § 4.3.3, § 5.7.1 (PandiO/knk-workspace#871): the situation names and their checks. */
class MenuItemSituationsTest {

    private final UUID player = UUID.randomUUID();
    private final MenuItemSituations situations = new MenuItemSituations();

    @Test
    void theThreeSiegeSituationsAreRegisteredMostSpecificFirst() {
        assertEquals(List.of("siege.survival.match", "siege.match", "siege.hub"), situations.names());
        assertTrue(situations.isKnown("siege.match"));
        assertFalse(situations.isKnown("arena.duel"));
    }

    @Test
    void aNameWithoutACheckNeverApplies_SoAFeatureThatIsOffLeavesItsOverrideUnused() {
        assertEquals(Optional.empty(), situations.situationOf(player));
        assertFalse(situations.isProvided(MenuItemSituations.SIEGE_SURVIVAL_MATCH));
        situations.provide(MenuItemSituations.SIEGE_HUB, id -> true);
        assertEquals(Optional.of("siege.hub"), situations.situationOf(player));
    }

    @Test
    void theOrderIsTheListNotTheOrderOfProviding() {
        situations.provide(MenuItemSituations.SIEGE_HUB, id -> true);
        situations.provide(MenuItemSituations.SIEGE_MATCH, id -> true);
        assertEquals(Optional.of("siege.match"), situations.situationOf(player));
    }

    @Test
    void aLaterFeatureAddsItsNameAfterTheBuiltInOnes() {
        situations.provide("arena.duel", id -> true);
        situations.provide(MenuItemSituations.SIEGE_HUB, id -> true);
        assertEquals("arena.duel", situations.names().get(3));
        assertEquals(Optional.of("siege.hub"), situations.situationOf(player));
        assertThrows(IllegalArgumentException.class, () -> situations.register(" "));
    }

    @Test
    void aBrokenCheckCountsAsNotApplying() {
        situations.provide(MenuItemSituations.SIEGE_MATCH, id -> {
            throw new IllegalStateException("boom");
        });
        situations.provide(MenuItemSituations.SIEGE_HUB, id -> true);
        assertEquals(Optional.of("siege.hub"), situations.situationOf(player));
        assertEquals(Optional.empty(), situations.situationOf(null));
    }

    @Test
    void unknownNamesAreListed() {
        assertEquals(List.of("arena.duel"), situations.unknown(List.of("siege.match", "arena.duel")));
    }
}
