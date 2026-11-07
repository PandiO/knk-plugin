package net.knightsandkings.knk.core.survival.mysterybox;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysteryBoxReelPlanTest {

    @Test
    void keepsTheApiReelAndSlowsDownToTheLastStep() {
        MysteryBoxReelPlan plan = MysteryBoxReelPlan.of(List.of(4, 2, 4, 9, 2), 2, 8);
        assertEquals(List.of(4, 2, 4, 9, 2), plan.entryIds());
        assertEquals(5, plan.steps());
        assertEquals(2, plan.resultEntryId());
        assertEquals(9, plan.entryAt(3));
        assertEquals(2, plan.entryAt(99));
        assertTrue(plan.isLast(4));
        assertEquals(5, plan.delayTicks().size());
        assertEquals(8, plan.delayTicks().get(4));
        for (int i = 1; i < plan.delayTicks().size(); i++) {
            assertTrue(plan.delayTicks().get(i) >= plan.delayTicks().get(i - 1), "ease-out: delays never shrink");
        }
        assertTrue(plan.delayTicks().get(0) >= 1);
        assertEquals(plan.delayTicks().stream().mapToInt(Integer::intValue).sum(), plan.totalTicks());
    }

    @Test
    void aReelThatDoesNotEndWithTheResultGetsTheResultAppended() {
        MysteryBoxReelPlan plan = MysteryBoxReelPlan.of(List.of(1, 3), 7, 4);
        assertEquals(List.of(1, 3, 7), plan.entryIds());
        assertEquals(List.of(7), MysteryBoxReelPlan.of(List.of(), 7, 4).entryIds());
        assertEquals(List.of(7), MysteryBoxReelPlan.of(null, 7, 4).entryIds());
    }

    @Test
    void rejectsAnEmptyReelOrMismatchedDelays() {
        assertThrows(IllegalArgumentException.class, () -> new MysteryBoxReelPlan(List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MysteryBoxReelPlan(List.of(1, 2), List.of(1)));
    }
}
