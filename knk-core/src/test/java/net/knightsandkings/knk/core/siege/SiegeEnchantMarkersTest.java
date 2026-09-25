package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.siege.SiegeEnchantMarkers.Entry;
import net.knightsandkings.knk.core.siege.SiegeEnchantMarkers.Reversion;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Siege Phase 5c (DESIGN §9.4, D7): siege-applied enchantments are recorded on the item and stripped
 * after the siege - reverted to the previous level or removed; stray books deleted; only members of
 * the book's match may pick it up.
 */
class SiegeEnchantMarkersTest {

    private static final String SHARPNESS = "minecraft:sharpness";
    private static final String UNBREAKING = "minecraft:unbreaking";
    private static final String MATCH_A = "match-a";
    private static final String MATCH_B = "match-b";

    private static Map<String, Integer> enchants(Object... keyLevel) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < keyLevel.length; i += 2) map.put((String) keyLevel[i], (Integer) keyLevel[i + 1]);
        return map;
    }

    @Test
    void strippingRevertsToThePreviousLevelOrRemovesTheEnchantment() {
        // Sword had Sharpness 1; the siege raised it to 2 and added Unbreaking 2.
        List<Entry> markers = SiegeEnchantMarkers.recordApplication(List.of(), MATCH_A, SHARPNESS, 1);
        markers = SiegeEnchantMarkers.recordApplication(markers, MATCH_A, UNBREAKING, 0);

        Reversion r = SiegeEnchantMarkers.revert(enchants(SHARPNESS, 2, UNBREAKING, 2), markers, token -> false);
        assertTrue(r.changed());
        assertEquals(enchants(SHARPNESS, 1), r.enchantments());
        assertTrue(r.remaining().isEmpty());
    }

    @Test
    void markersOfTheRunningMatchStay() {
        List<Entry> markers = SiegeEnchantMarkers.recordApplication(List.of(), MATCH_A, SHARPNESS, 0);
        Reversion r = SiegeEnchantMarkers.revert(enchants(SHARPNESS, 2), markers, MATCH_A::equals);
        assertFalse(r.changed());
        assertEquals(enchants(SHARPNESS, 2), r.enchantments());
        assertEquals(markers, r.remaining());
    }

    @Test
    void aSecondApplicationInTheSameMatchKeepsThePreSiegeLevel() {
        List<Entry> markers = SiegeEnchantMarkers.recordApplication(List.of(), MATCH_A, SHARPNESS, 0);
        markers = SiegeEnchantMarkers.recordApplication(markers, MATCH_A, SHARPNESS, 1); // book 1, then book 2
        assertEquals(1, markers.size());
        assertEquals(enchants(), SiegeEnchantMarkers.revert(enchants(SHARPNESS, 2), markers, t -> false).enchantments());
    }

    @Test
    void withMarkersFromTwoMatchesTheOldestPreviousLevelWins() {
        // A crash left match A's marker; match B then raised it again.
        List<Entry> markers = SiegeEnchantMarkers.recordApplication(List.of(), MATCH_A, SHARPNESS, 1);
        markers = SiegeEnchantMarkers.recordApplication(markers, MATCH_B, SHARPNESS, 2);
        Reversion r = SiegeEnchantMarkers.revert(enchants(SHARPNESS, 3), markers, t -> false);
        assertEquals(enchants(SHARPNESS, 1), r.enchantments());
    }

    @Test
    void encodeDecodeRoundTripsAndDecodeIsLenient() {
        List<Entry> markers = List.of(new Entry(MATCH_A, SHARPNESS, 1), new Entry(MATCH_B, UNBREAKING, null));
        String encoded = SiegeEnchantMarkers.encode(markers);
        assertEquals("match-a;minecraft:sharpness;1|match-b;minecraft:unbreaking;-", encoded);
        assertEquals(markers, SiegeEnchantMarkers.decode(encoded));
        assertEquals(List.of(new Entry(MATCH_A, SHARPNESS, 1)),
                SiegeEnchantMarkers.decode("garbage|match-a;minecraft:sharpness;1|x;y;notanumber|;;"));
        assertTrue(SiegeEnchantMarkers.decode(null).isEmpty());
        assertTrue(SiegeEnchantMarkers.decode("").isEmpty());
    }

    @Test
    void appliedLevelIsTheHigherOfExistingAndBook() {
        assertEquals(2, SiegeEnchantMarkers.appliedLevel(0, 2));
        assertEquals(3, SiegeEnchantMarkers.appliedLevel(3, 2));
        assertEquals(2, SiegeEnchantMarkers.appliedLevel(2, 2));
    }

    @Test
    void onlyMembersOfTheBooksMatchMayPickItUp() {
        assertTrue(SiegeEnchantMarkers.mayPickUp(MATCH_A, MATCH_A));
        assertFalse(SiegeEnchantMarkers.mayPickUp(MATCH_A, null), "non-member");
        assertFalse(SiegeEnchantMarkers.mayPickUp(MATCH_A, MATCH_B), "member of another match");
        assertFalse(SiegeEnchantMarkers.mayPickUp(null, MATCH_A), "not a siege book");
    }

    @Test
    void strayBooksAreDeletedBySweepUnlessTheirMatchIsRunningForThePlayer() {
        assertFalse(SiegeEnchantMarkers.keepBook(MATCH_A, null), "after the match: stray, deleted");
        assertFalse(SiegeEnchantMarkers.keepBook(MATCH_A, MATCH_B));
        assertTrue(SiegeEnchantMarkers.keepBook(MATCH_A, MATCH_A));
    }
}
