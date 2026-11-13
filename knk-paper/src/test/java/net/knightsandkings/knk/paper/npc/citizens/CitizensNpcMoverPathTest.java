package net.knightsandkings.knk.paper.npc.citizens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.NavPoint;

/** PandiO/knk-workspace#361: the rest of the engine's path, for the recheck of a local leg. */
class CitizensNpcMoverPathTest {

    @Test
    void thePathStartsAtThePointNearestTheNpc() {
        List<Vector> path = List.of(new Vector(0, 64, 0), new Vector(1, 64, 0), new Vector(2, 65, 0), new Vector(3, 65, 0));

        List<NavPoint> ahead = CitizensNpcMover.aheadOf("world", new Vector(2.2, 65, 0.4), path);

        assertEquals(List.of(new NavPoint("world", 2.5, 65, 0.5), new NavPoint("world", 3.5, 65, 0.5)), ahead,
            "what lies behind the NPC is left out; feet positions at block centres");
    }

    @Test
    void anEmptyPathHasNothingAhead() {
        assertTrue(CitizensNpcMover.aheadOf("world", new Vector(0, 64, 0), List.of()).isEmpty());
    }
}
