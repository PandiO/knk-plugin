package net.knightsandkings.knk.paper.gates;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * R25: the same outcomes GatePassThroughConsequenceListenerTest checks, on the extracted predicate.
 * Written for the developer's local build (paper-api is not resolvable in the cloud).
 */
class GatePassThroughRulesTest {
    private CachedGateDoor gate;
    private Player player;

    @BeforeEach
    void setUp() {
        gate = new CachedGateDoor(
            1, 1, "TestGate", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(100, 64, 100), 5, 5, 3,
            500.0, 500.0, true, false, false, 90, "north"
        );
        player = mock(Player.class);
    }

    @Test
    void aGateAdminPassesAnyDoor() {
        gate.setAllowPassThrough(false);
        when(player.hasPermission(GatePassThroughRules.ADMIN_NODE)).thenReturn(true);
        assertTrue(GatePassThroughRules.canPass(player, gate));
        assertTrue(GatePassThroughRules.isAdmin(player));
    }

    @Test
    void aDoorThatDoesNotAllowPassThroughStopsEveryoneElse() {
        gate.setAllowPassThrough(false);
        when(player.hasPermission(GatePassThroughRules.USE_NODE)).thenReturn(true);
        assertFalse(GatePassThroughRules.canPass(player, gate));
    }

    @Test
    void theUseNodeIsNeededOnAPassThroughDoor() {
        gate.setAllowPassThrough(true);
        when(player.hasPermission(GatePassThroughRules.USE_NODE)).thenReturn(false);
        assertFalse(GatePassThroughRules.canPass(player, gate));
        when(player.hasPermission(GatePassThroughRules.USE_NODE)).thenReturn(true);
        assertTrue(GatePassThroughRules.canPass(player, gate));
    }
}
