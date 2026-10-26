package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeSpawnpoint;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.domain.siege.SiegeGateState;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.gates.GateManager;

/** PandiO/knk-workspace#176: the gate cache side of the approach point (alpha seed's Northern Gate). */
class SiegeGateApproachesTest {

    private static final String WORLD = "world_KNK-DEV";
    private final GateManager gates = mock(GateManager.class);
    private final CachedGateDoor outer = mock(CachedGateDoor.class);
    private final CachedGateDoor inner = mock(CachedGateDoor.class);
    private final KnkSiegeObjective northernGate = new KnkSiegeObjective(3, 2, "Northern Gate",
        new KnkLocation(18, "Location", 1386.3, 52.0, -588.2, 0f, 0f, WORLD), 8, 500, 2.5, false, 1, true, SiegeGateState.OPEN);
    private final KnkSiegeTeam attackers = new KnkSiegeTeam(2, 1, SiegeTeamRole.ATTACKER, 2, null, "Attacker", "RED", null, null,
        List.of(new KnkSiegeSpawnpoint(3, 1, "North Spawn", new KnkLocation(49, "L", 1324.5, 45.0, -622.2, 0f, 0f, WORLD), 4)));

    private static List<Vector> diagonal(int x0, int z0, int width, int y0, int y1) {
        List<Vector> cells = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            for (int y = y0; y <= y1; y++) {
                cells.add(new Vector(x0 + i, y, z0 - i));
            }
        }
        return cells;
    }

    @BeforeEach
    void setUp() {
        when(outer.getId()).thenReturn(4);
        when(outer.getName()).thenReturn("Outer Gate");
        when(inner.getId()).thenReturn(5);
        when(inner.getName()).thenReturn("Inner Gate");
        when(gates.getDoorsForStructure(8)).thenReturn(List.of(outer, inner));
        when(gates.closedFootprint(4)).thenReturn(diagonal(1375, -585, 4, 44, 51));
        when(gates.closedFootprint(5)).thenReturn(diagonal(1380, -581, 3, 45, 50));
        when(gates.openFootprint(4)).thenReturn(List.of());
        when(gates.openFootprint(5)).thenReturn(List.of());
        when(outer.getCurrentState()).thenReturn(AnimationState.CLOSED);
        when(inner.getCurrentState()).thenReturn(AnimationState.CLOSED);
    }

    @Test
    void closedGatesSendAttackersToTheOuterDoor() {
        KnkLocation at = SiegeGateApproaches.of(gates).approach(northernGate, attackers).orElseThrow();
        assertEquals(44.0, at.y());
        assertTrue(at.x() < 1377 && at.z() < -586, at.toString());
        assertEquals(WORLD, at.world());
    }

    @Test
    void anOpenOrDestroyedOuterDoorLeavesTheCapturePoint() {
        when(outer.getCurrentState()).thenReturn(AnimationState.OPEN);
        assertEquals(Optional.empty(), SiegeGateApproaches.of(gates).approach(northernGate, attackers));

        when(outer.getCurrentState()).thenReturn(AnimationState.CLOSED);
        when(outer.isEffectivelyDestroyed()).thenReturn(true);
        assertEquals(Optional.empty(), SiegeGateApproaches.of(gates).approach(northernGate, attackers));
    }

    @Test
    void anObjectiveWithoutAGateIsLeftAlone() {
        KnkSiegeObjective throneroom = new KnkSiegeObjective(1, 0, "Throneroom",
            new KnkLocation(47, "L", 1421.5, 86.0, -494.7, 0f, 0f, WORLD), null, 500, 3, true, 1, false, SiegeGateState.OPEN);
        assertEquals(Optional.empty(), SiegeGateApproaches.of(gates).approach(throneroom, attackers));
    }
}
