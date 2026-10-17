package net.knightsandkings.knk.paper.tasks;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** KNG-113: a door's region is captured only in the door's own world. */
class GateRegionWorldTest {

    @Test
    void aDoorsRegionIsDrawnOnlyInItsOwnWorld() {
        assertNull(GateRegionWorld.wrongWorld("hub", "HUB", "Left"));
        assertNull(GateRegionWorld.wrongWorld("", "world", "Left"), "a door without a world: as before");
        String refusal = GateRegionWorld.wrongWorld("hub", "world", "Left");
        assertTrue(refusal != null && refusal.contains("is in world 'hub', but you are in 'world'"), refusal);
    }
}
