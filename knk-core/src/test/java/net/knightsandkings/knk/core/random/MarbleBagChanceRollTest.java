package net.knightsandkings.knk.core.random;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarbleBagChanceRollTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void eachPlayerAndKeyHasItsOwnBag() {
        MarbleBagChanceRoll roll = new MarbleBagChanceRoll(ChanceBag.Size.DEFAULT, new Random(1));
        int alicePoison = 0;
        int aliceFreeze = 0;
        int bobPoison = 0;
        // Interleaved: 8 draws each from three bags of 25%. Every bag still gives exactly 2 hits.
        for (int i = 0; i < 8; i++) {
            if (roll.roll(ALICE, "poison", 0.25d)) alicePoison++;
            if (roll.roll(BOB, "poison", 0.25d)) bobPoison++;
            if (roll.roll(ALICE, "freeze", 0.25d)) aliceFreeze++;
        }
        assertEquals(2, alicePoison);
        assertEquals(2, aliceFreeze);
        assertEquals(2, bobPoison);
    }

    @Test
    void aChangedChanceStartsANewBag() {
        MarbleBagChanceRoll roll = new MarbleBagChanceRoll(ChanceBag.Size.DEFAULT, new Random(2));
        for (int i = 0; i < 3; i++) {
            roll.roll(ALICE, "poison", 0.25d);
        }
        // Level II: 50%, a new bag of 8 with 4 hits, whatever was left of the old one.
        int hits = 0;
        for (int i = 0; i < 8; i++) {
            if (roll.roll(ALICE, "poison", 0.5d)) hits++;
        }
        assertEquals(4, hits);
    }

    @Test
    void forgetDropsAPlayersBags() {
        MarbleBagChanceRoll roll = new MarbleBagChanceRoll(ChanceBag.Size.DEFAULT, new Random(3));
        for (int i = 0; i < 5; i++) {
            roll.roll(ALICE, "poison", 0.25d);
        }
        roll.forget(ALICE);
        roll.forget(null);

        int hits = 0;
        for (int i = 0; i < 8; i++) {
            if (roll.roll(ALICE, "poison", 0.25d)) hits++;
        }
        assertEquals(2, hits);
    }

    @Test
    void neverAlwaysAndUnknownSubject() {
        MarbleBagChanceRoll roll = new MarbleBagChanceRoll(ChanceBag.Size.DEFAULT, new Random(4));
        assertFalse(roll.roll(ALICE, "poison", 0.0d));
        assertFalse(roll.roll(ALICE, "poison", Double.NaN));
        assertTrue(roll.roll(ALICE, "poison", 1.0d));

        int hits = 0;
        for (int i = 0; i < 40_000; i++) {
            if (roll.roll(null, "poison", 0.25d)) hits++;
        }
        assertEquals(0.25d, hits / 40_000.0d, 0.02d);
    }

    @Test
    void independentRollsCompareTheRollWithTheChance() {
        assertTrue(ChanceRoll.independent(() -> 0.10d).roll(ALICE, "poison", 0.15d));
        assertFalse(ChanceRoll.independent(() -> 0.30d).roll(ALICE, "poison", 0.15d));
    }
}
