package net.knightsandkings.knk.paper.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** R11: the one lag check, with the historical semantics of GateBlockScanTaskHandler's three copies. */
class TickBudgetTest {

    @Test
    void below15TpsIsLagging() {
        assertTrue(new TickBudget(() -> 14.9).isLagging());
        assertFalse(new TickBudget(() -> 15.0).isLagging());
        assertFalse(new TickBudget(() -> 20.0).isLagging());
    }

    @Test
    void perTickPicksTheBudget() {
        assertEquals(50, new TickBudget(() -> 10).perTick(200, 50));
        assertEquals(200, new TickBudget(() -> 19.5).perTick(200, 50));
    }

    @Test
    void anUnreadableTpsCountsAsNotLagging() {
        TickBudget broken = new TickBudget(() -> {
            throw new IllegalStateException("no server");
        });
        assertFalse(broken.isLagging());
        assertEquals(200, broken.perTick(200, 50));
    }
}
