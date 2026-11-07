package net.knightsandkings.knk.core.survival.kits;

import net.knightsandkings.knk.core.survival.kits.KitSelection.KitOption;
import net.knightsandkings.knk.core.survival.kits.KitSelection.PickResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitSelectionTest {

    private final UUID player = UUID.randomUUID();
    private final KitSelection selection = new KitSelection(
        List.of(new KitOption(3, "Archer", 1), new KitOption(5, "Knight", 0), new KitOption(7, "Knave", 2)), OptionalInt.of(5));

    @Test
    void noPickMeansTheDefaultKit() {
        KitSelection.Resolution r = selection.resolve(player).orElseThrow();
        assertEquals(5, r.kitId());
        assertTrue(r.defaulted());
        assertTrue(selection.worthAsking());
    }

    @Test
    void aPickWinsUntilTheMatchLocks() {
        assertEquals(PickResult.PICKED, selection.pick(player, 3));
        assertEquals(3, selection.resolve(player).orElseThrow().kitId());
        assertFalse(selection.resolve(player).orElseThrow().defaulted());
        assertEquals(PickResult.UNKNOWN_KIT, selection.pick(player, 99));
        assertEquals(3, selection.picked(player).orElseThrow());
        selection.lock();
        assertEquals(PickResult.LOCKED, selection.pick(player, 7));
        assertEquals(3, selection.resolve(player).orElseThrow().kitId());
    }

    @Test
    void namesResolveExactlyOrByUniquePrefix() {
        assertEquals(3, selection.optionByName("archer").orElseThrow().kitId());
        assertEquals(3, selection.optionByName("Arc").orElseThrow().kitId());
        assertTrue(selection.optionByName("Kn").isEmpty());
        assertEquals(5, selection.optionByName("Kni").orElseThrow().kitId());
        assertTrue(selection.optionByName(null).isEmpty());
    }

    @Test
    void withoutADefaultTheFirstOptionIsUsedAndAnEmptySelectionHasNoKit() {
        KitSelection noDefault = new KitSelection(List.of(new KitOption(9, "Only", 0)), OptionalInt.empty());
        assertEquals(9, noDefault.resolve(player).orElseThrow().kitId());
        assertFalse(noDefault.worthAsking());
        assertTrue(new KitSelection(List.of(), OptionalInt.of(1)).resolve(player).isEmpty());
        KitSelection wrongDefault = new KitSelection(List.of(new KitOption(9, "Only", 0)), OptionalInt.of(1));
        assertEquals(9, wrongDefault.resolve(player).orElseThrow().kitId());
    }
}
