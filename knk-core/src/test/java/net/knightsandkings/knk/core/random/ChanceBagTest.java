package net.knightsandkings.knk.core.random;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChanceBagTest {

    @Test
    void picksTheSmallestBagWithWholeHits() {
        ChanceBag.Size size = ChanceBag.Size.DEFAULT;
        assertEquals(8, size.bagSizeFor(0.25d));
        assertEquals(8, size.bagSizeFor(0.5d));
        assertEquals(10, size.bagSizeFor(0.10d));
        assertEquals(10, size.bagSizeFor(0.30d));
        assertEquals(20, size.bagSizeFor(0.15d));
        assertEquals(20, size.bagSizeFor(0.05d));
        assertEquals(9, size.bagSizeFor(1.0d / 3.0d));
        // No bag up to 20 holds a whole number of hits: the largest one, with a carry.
        assertEquals(20, size.bagSizeFor(0.01d));
        assertEquals(20, size.bagSizeFor(0.37d));
    }

    @Test
    void aQuarterIsTwoHitsInEveryEightDraws() {
        ChanceBag bag = new ChanceBag(0.25d, ChanceBag.Size.DEFAULT, new Random(42));
        assertEquals(8, bag.bagSize());

        int longestMissStreak = 0;
        int missStreak = 0;
        for (int round = 0; round < 5_000; round++) {
            int hits = 0;
            for (int i = 0; i < 8; i++) {
                if (bag.next()) {
                    hits++;
                    missStreak = 0;
                } else {
                    longestMissStreak = Math.max(longestMissStreak, ++missStreak);
                }
            }
            assertEquals(2, hits, "round " + round);
        }
        // Hits first in one bag and last in the next: 6 + 6 misses at most. Independent rolls would pass 12
        // many times in 40 000 draws.
        assertTrue(longestMissStreak <= 12, "longest miss streak " + longestMissStreak);
    }

    @Test
    void aChanceThatFitsNoBagKeepsItsAverageThroughTheCarry() {
        assertEquals(0.01d, hitRate(0.01d, 20 * 20_000), 0.0001d);
        assertEquals(0.37d, hitRate(0.37d, 20 * 20_000), 0.0001d);
        assertEquals(0.15d, hitRate(0.15d, 20 * 20_000), 0.0001d);
    }

    @Test
    void aRareChanceCanHitInTheFirstBags() {
        // 1% in bags of 20: one hit per five bags. The carry starts at a random point, so some players get it early.
        // One generator for all: java.util.Random's first value is nearly the same for seeds that are close together.
        Random random = new Random(77);
        int hitInFirstBag = 0;
        for (int player = 0; player < 2_000; player++) {
            ChanceBag bag = new ChanceBag(0.01d, ChanceBag.Size.DEFAULT, random);
            for (int i = 0; i < 20; i++) {
                if (bag.next()) {
                    hitInFirstBag++;
                    break;
                }
            }
        }
        // About one in five.
        assertTrue(hitInFirstBag > 300 && hitInFirstBag < 500, "hit in the first bag: " + hitInFirstBag);
    }

    @Test
    void neverAndAlways() {
        ChanceBag never = new ChanceBag(-0.5d, ChanceBag.Size.DEFAULT, new Random(1));
        ChanceBag notANumber = new ChanceBag(Double.NaN, ChanceBag.Size.DEFAULT, new Random(1));
        ChanceBag always = new ChanceBag(1.5d, ChanceBag.Size.DEFAULT, new Random(1));
        for (int i = 0; i < 100; i++) {
            assertFalse(never.next());
            assertFalse(notANumber.next());
            assertTrue(always.next());
        }
        assertEquals(1.0d, always.chance());
    }

    @Test
    void sizeIsKeptValid() {
        ChanceBag.Size size = new ChanceBag.Size(0, -3);
        assertEquals(1, size.min());
        assertEquals(1, size.max());
        // A bag of one is an independent roll: the average still holds.
        ChanceBag bag = new ChanceBag(0.25d, size, new Random(9));
        int hits = 0;
        for (int i = 0; i < 40_000; i++) {
            if (bag.next()) {
                hits++;
            }
        }
        assertEquals(0.25d, hits / 40_000.0d, 0.0001d);
    }

    private static double hitRate(double chance, int draws) {
        ChanceBag bag = new ChanceBag(chance, ChanceBag.Size.DEFAULT, new Random(123));
        int hits = 0;
        for (int i = 0; i < draws; i++) {
            if (bag.next()) {
                hits++;
            }
        }
        return hits / (double) draws;
    }
}
