package net.knightsandkings.knk.core.lootbox;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The opening reel (DESIGN.md §3.9): stops on the rolled item, eases out, draws the rest with the real odds. */
class LootboxReelTest {

    private static final List<LootboxReel.Weighted<String>> ODDS = List.of(
            new LootboxReel.Weighted<>("common", 80),
            new LootboxReel.Weighted<>("rare", 20));

    @Test
    void theLastStepLeavesTheWinnerUnderTheMarker() {
        LootboxReel<String> reel = LootboxReel.plan(ODDS, "samurai", 32, 9, 8, new Random(1)::nextDouble);

        assertEquals(32, reel.steps());
        assertEquals(41, reel.items().size());
        assertEquals(4, reel.markerOffset());
        assertEquals("samurai", reel.atMarker(reel.steps()));
        assertEquals(9, reel.window(0).size());
        assertEquals(reel.items().subList(32, 41), reel.window(32));
    }

    @Test
    void itStartsFastAndSlowsDown() {
        LootboxReel<String> reel = LootboxReel.plan(ODDS, "x", 10, 9, 8, () -> 0.0);

        assertEquals(1, reel.delays().get(0));
        assertEquals(8, reel.delays().get(9));
        for (int i = 1; i < reel.steps(); i++) {
            assertTrue(reel.delays().get(i) >= reel.delays().get(i - 1), "never speeds up again");
        }
        assertEquals(reel.delays().stream().mapToInt(Integer::intValue).sum(), reel.totalTicks());
    }

    @Test
    void thePassingItemsFollowTheOdds() {
        LootboxReel<String> reel = LootboxReel.plan(ODDS, "winner", 20_000, 9, 1, new Random(7)::nextDouble);

        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < reel.items().size(); i++) {
            if (i != reel.steps() + reel.markerOffset()) {
                seen.merge(reel.items().get(i), 1, Integer::sum);
            }
        }
        double rare = seen.getOrDefault("rare", 0) / (double) (reel.items().size() - 1);
        assertEquals(0.20, rare, 0.02);
        assertNull(seen.get("winner"), "the winner appears only where it stops");
    }

    @Test
    void withoutCandidates_theReelShowsOnlyTheWinner() {
        LootboxReel<String> reel = LootboxReel.plan(List.of(), "only", 3, 3, 4, () -> 0.5);

        assertTrue(reel.items().stream().allMatch("only"::equals));
    }

    @Test
    void aWinnerIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> LootboxReel.plan(ODDS, null, 3, 9, 4, () -> 0.5));
    }

    @Test
    void perSlot_dressesEveryPassingItemSeparately_butNeverTheWinner() {
        int[] counter = {0};
        LootboxReel<String> reel = LootboxReel.plan(ODDS, "winner", 10, 9, 8, new Random(2)::nextDouble,
                item -> item + "#" + counter[0]++);

        assertEquals("winner", reel.atMarker(reel.steps()));
        assertEquals(18, reel.items().stream().filter(item -> item.contains("#")).count(), "every slot but the winner");
        assertEquals(reel.items().size() - 1, reel.items().stream().distinct().filter(item -> !item.equals("winner")).count()
                , "each passing slot got its own copy");
    }
}
