package net.knightsandkings.knk.core.siege;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Phase 5: the in-match command filter (DESIGN §6.9, fixes N10). */
class SiegeCommandFilterTest {

    private static final List<String> LEGACY = List.of("/siege", "/msg", "/r", "/staffchat", "/menu");

    @Test
    void allowsListedCommandsWithArgumentsAndAnyCase() {
        assertTrue(SiegeCommandFilter.isAllowed("/msg Bob hello", LEGACY));
        assertTrue(SiegeCommandFilter.isAllowed("/MSG Bob", LEGACY));
        assertTrue(SiegeCommandFilter.isAllowed("/r ok", LEGACY));
        assertTrue(SiegeCommandFilter.isAllowed("/menu", LEGACY));
    }

    @Test
    void blocksEverythingElseIncludingPrefixLookalikes() {
        assertFalse(SiegeCommandFilter.isAllowed("/spawn", LEGACY));
        assertFalse(SiegeCommandFilter.isAllowed("/msgall hi", LEGACY));
        assertFalse(SiegeCommandFilter.isAllowed("/kit get Archer", LEGACY));
        assertFalse(SiegeCommandFilter.isAllowed("/reply hi", LEGACY), "aliases aren't resolved: list them");
    }

    @Test
    void namespacedFormsAreNormalisedSoTheyCantBypassTheList() {
        assertFalse(SiegeCommandFilter.isAllowed("/minecraft:tp 0 0 0", LEGACY));
        assertTrue(SiegeCommandFilter.isAllowed("/knightsandkings:msg Bob", LEGACY));
        assertTrue(SiegeCommandFilter.isAllowed("/msg x", List.of("knightsandkings:msg")));
    }

    @Test
    void siegeIsAlwaysAllowedSoMembersCanLeave() {
        assertTrue(SiegeCommandFilter.isAllowed("/siege leave", List.of()));
        assertTrue(SiegeCommandFilter.isAllowed("/knightsandkings:siege vote 1", null));
        assertFalse(SiegeCommandFilter.isAllowed("/msg x", null));
    }

    @Test
    void labelStripsSlashNamespaceAndArguments() {
        assertEquals("siege", SiegeCommandFilter.label("/KnK:Siege join cinix"));
        assertEquals("msg", SiegeCommandFilter.label("msg"));
        assertEquals("", SiegeCommandFilter.label("/"));
        assertEquals("", SiegeCommandFilter.label(null));
    }
}
