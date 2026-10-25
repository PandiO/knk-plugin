package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * GateCrackVisuals: a damaged gate's door blocks show vanilla's 10 block-breaking stages, divided over the
 * gate's HP, and only while the gate is closed and standing.
 */
class GateCrackVisualsTest {

    private GateManager gateManager;
    private GateCrackVisuals visuals;
    private CachedGateDoor gate;
    private World world;
    private Player viewer;
    private MockedStatic<Bukkit> bukkitMock;

    @BeforeEach
    void setUp() {
        gateManager = new GateManager();
        visuals = new GateCrackVisuals(gateManager);

        gate = new CachedGateDoor(
            1, 1, "TestGate", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(100, 64, 100), 5, 5, 3,
            500.0, 500.0, true, false, false, 90, "north"
        );
        gate.setWorldName("world");
        gate.setUAxis(new Vector(1, 0, 0));
        gate.setVAxis(new Vector(0, 1, 0));
        gate.setNAxis(new Vector(0, 0, 1));
        gate.setMotionVector(new Vector(0, 3, 0));
        gate.setCurrentState(AnimationState.CLOSED);
        gate.setCurrentFrame(0);
        gate.addBlock(new BlockSnapshot(1, new Vector(0, 0, 0), 1, "stone", 0));
        gate.addBlock(new BlockSnapshot(2, new Vector(1, 0, 0), 1, "stone", 0));
        gateManager.cacheGate(gate);

        world = mock(World.class);
        viewer = mock(Player.class);
        when(world.getNearbyPlayers(any(Location.class), anyDouble())).thenReturn(List.of(viewer));
        bukkitMock = mockStatic(Bukkit.class);
        bukkitMock.when(() -> Bukkit.getWorld(anyString())).thenReturn(world);
    }

    @AfterEach
    void tearDown() {
        bukkitMock.close();
    }

    // ===== the stages =====

    @Test
    void anUndamagedGateShowsNoCracks() {
        assertEquals(-1, GateCrackVisuals.stageFor(500, 500));
        assertEquals(-1, GateCrackVisuals.stageFor(600, 500));
        assertEquals(-1, GateCrackVisuals.stageFor(10, 0));
    }

    @Test
    void theHpIsDividedOverTenStages() {
        assertEquals(0, GateCrackVisuals.stageFor(495, 500));   // 1% lost: the first cracks
        assertEquals(0, GateCrackVisuals.stageFor(451, 500));   // just under a tenth lost
        assertEquals(1, GateCrackVisuals.stageFor(449, 500));   // just over a tenth lost
        assertEquals(4, GateCrackVisuals.stageFor(290, 500));   // 42% lost
        assertEquals(5, GateCrackVisuals.stageFor(250, 500));   // half lost
        assertEquals(8, GateCrackVisuals.stageFor(80, 500));    // 84% lost
        assertEquals(9, GateCrackVisuals.stageFor(10, 500));    // 98% lost: about to break
        assertEquals(9, GateCrackVisuals.stageFor(0, 500));     // never past vanilla's last stage
        assertEquals(9, GateCrackVisuals.stageFor(-5, 500));
    }

    @Test
    void everyStageGoesOutAsAProgressPaperTurnsBackIntoThatStage() {
        // Paper sends (int) (9 * progress) and reads progress 0 as "clear"
        for (int stage = 0; stage < GateCrackVisuals.STAGES; stage++) {
            float progress = GateCrackVisuals.progressFor(stage);
            assertTrue(progress > 0.0f && progress <= 1.0f, "stage " + stage);
            assertEquals(stage, (int) (9 * progress), "stage " + stage);
        }
    }

    @Test
    void everyBlockOfEveryGateHasItsOwnNegativeSourceId() {
        Set<Integer> seen = new HashSet<>();
        for (int gateId : new int[] {1, 2, 300, 32767}) {
            for (int index = 0; index < 200; index++) {
                int id = GateCrackVisuals.sourceId(gateId, index);
                assertTrue(id < 0, "entity ids are never negative, so this can't clash with one");
                assertTrue(seen.add(id), "duplicate id for gate " + gateId + " block " + index);
            }
        }
    }

    @Test
    void onlyAClosedStandingGateHasCracks() {
        gate.setHealthCurrent(250);
        assertEquals(5, GateCrackVisuals.targetStage(gate));

        gate.setCurrentState(AnimationState.OPENING);
        assertEquals(-1, GateCrackVisuals.targetStage(gate));
        gate.setCurrentState(AnimationState.OPEN);
        assertEquals(-1, GateCrackVisuals.targetStage(gate));

        gate.setCurrentState(AnimationState.CLOSED);
        gate.setIsDestroyed(true);
        assertEquals(-1, GateCrackVisuals.targetStage(gate));
    }

    // ===== sending =====

    @Test
    void aDamagedGateSendsItsStageForEveryBlockToNearbyPlayers() {
        gate.setHealthCurrent(250);

        visuals.sync(gate, false);

        float progress = GateCrackVisuals.progressFor(5);
        verify(viewer).sendBlockDamage(eq(new Location(world, 100, 64, 100)), eq(progress), eq(GateCrackVisuals.sourceId(1, 0)));
        verify(viewer).sendBlockDamage(eq(new Location(world, 101, 64, 100)), eq(progress), eq(GateCrackVisuals.sourceId(1, 1)));
        verifyNoMoreInteractions(viewer);
        assertEquals(5, visuals.shownStage(gate));
    }

    @Test
    void aFullHpGateSendsNothing() {
        visuals.sync(gate, true);

        verifyNoInteractions(viewer);
        assertEquals(-1, visuals.shownStage(gate));
    }

    @Test
    void theSameStageIsOnlySentAgainOnAResend() {
        gate.setHealthCurrent(250);
        visuals.sync(gate, false);
        clearInvocations(viewer);

        visuals.sync(gate, false);
        verifyNoInteractions(viewer);

        visuals.sync(gate, true);
        verify(viewer, times(2)).sendBlockDamage(any(Location.class), eq(GateCrackVisuals.progressFor(5)), anyInt());
    }

    @Test
    void moreDamageMovesTheBlocksToTheNextStage() {
        gate.setHealthCurrent(450);
        visuals.sync(gate, false);
        clearInvocations(viewer);

        gate.setHealthCurrent(60);
        visuals.sync(gate, false);

        verify(viewer, times(2)).sendBlockDamage(any(Location.class), eq(GateCrackVisuals.progressFor(8)), anyInt());
        assertEquals(8, visuals.shownStage(gate));
    }

    @Test
    void aGateThatOpensTakesItsCracksDownOnce() {
        gate.setHealthCurrent(250);
        visuals.sync(gate, false);
        clearInvocations(viewer);

        gate.setCurrentState(AnimationState.OPENING);
        visuals.sync(gate, false);

        verify(viewer, times(2)).sendBlockDamage(any(Location.class), eq(0.0f), anyInt());
        assertEquals(-1, visuals.shownStage(gate));

        clearInvocations(viewer);
        visuals.sync(gate, true);
        verifyNoInteractions(viewer);
    }

    @Test
    void aGateThatClosesAgainShowsItsCracksAgain() {
        gate.setHealthCurrent(250);
        gate.setCurrentState(AnimationState.OPEN);
        visuals.sync(gate, false);
        verifyNoInteractions(viewer);

        gate.setCurrentState(AnimationState.CLOSED);
        visuals.sync(gate, false);

        verify(viewer, times(2)).sendBlockDamage(any(Location.class), eq(GateCrackVisuals.progressFor(5)), anyInt());
    }

    @Test
    void aDestroyedOrRepairedGateLosesItsCracks() {
        gate.setHealthCurrent(100);
        visuals.sync(gate, false);
        clearInvocations(viewer);

        gate.setHealthCurrent(gate.getHealthMax()); // respawn / repair
        visuals.sync(gate, false);

        verify(viewer, times(2)).sendBlockDamage(any(Location.class), eq(0.0f), anyInt());
    }

    @Test
    void theTaskResendsEverythingOnItsSlowCycle() {
        gate.setHealthCurrent(250);
        GateCrackVisualsTask task = new GateCrackVisualsTask(visuals);

        task.run(); // run 1: the change goes out
        verify(viewer, times(2)).sendBlockDamage(any(Location.class), anyFloat(), anyInt());
        clearInvocations(viewer);

        for (int run = 2; run < GateCrackVisualsTask.RESEND_EVERY; run++) {
            task.run();
        }
        verifyNoInteractions(viewer);

        task.run(); // the resend run
        verify(viewer, times(2)).sendBlockDamage(any(Location.class), anyFloat(), anyInt());
    }

    @Test
    void aNullGateIsIgnored() {
        assertDoesNotThrow(() -> visuals.sync(null, true));
    }
}
