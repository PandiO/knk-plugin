package net.knightsandkings.knk.paper.lootbox;

import net.knightsandkings.knk.core.lootbox.KnkLootboxOdds;
import net.knightsandkings.knk.core.lootbox.LootboxReel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What passes by on the reel: the box's items as the box gives them, and its specials as they are. */
class LootboxOpeningCandidatesTest {

    @Test
    void items_keepTheirGradeQuantityAndWhetherTheyRollEnchantments() {
        KnkLootboxOdds odds = new KnkLootboxOdds(3, "Weapons Lootbox", 5, 99.5, List.of(),
                List.of(new KnkLootboxOdds.Item("Golemheart Sword", 5, 18.7, 50, 1, true),
                        new KnkLootboxOdds.Item("Bread", 2, 5, 51, 16, false),
                        new KnkLootboxOdds.Item("No blueprint", 2, 5, null, 1, true),
                        new KnkLootboxOdds.Item("Never", 2, 0, 52, 1, true)),
                List.of());

        List<LootboxReel.Weighted<LootboxOpening.Candidate>> weighted = LootboxOpening.weighted(odds);

        assertEquals(List.of(
                new LootboxReel.Weighted<>(new LootboxOpening.Candidate(50, 5, 1, true), 18.7),
                new LootboxReel.Weighted<>(new LootboxOpening.Candidate(51, 2, 16, false), 5.0)), weighted);
    }

    @Test
    void specials_keepTheirOwnGradeQuantityAndRollNothing() {
        KnkLootboxOdds odds = new KnkLootboxOdds(3, "Weapons Lootbox", 5, 99.5, List.of(), List.of(),
                List.of(new KnkLootboxOdds.Special("Flaming Samurai", 0.05, 90), new KnkLootboxOdds.Special("Gone", 0.2, null)));

        assertEquals(List.of(new LootboxReel.Weighted<>(new LootboxOpening.Candidate(90, 0, 0, false), 0.05)),
                LootboxOpening.weighted(odds));
    }
}
