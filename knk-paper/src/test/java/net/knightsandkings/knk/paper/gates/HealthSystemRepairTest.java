package net.knightsandkings.knk.paper.gates;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.knightsandkings.knk.api.GateDoorsApi;
import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Siege Survival link S3 (PandiO/knk-workspace#41; implementation checks report §7): the two public heal entry
 * points for Survival repairs (link S6). {@code repairToFull} heals a standing door and reports the HP restored;
 * {@code rebuildForClosing} turns a destroyed door into a standing one that waits OPEN at the last frame for the
 * caller's animated {@code closeGate}, placing no blocks and broadcasting nothing. Both tell the state listeners.
 */
class HealthSystemRepairTest {

    private MockedStatic<Bukkit> bukkit;
    private HealthSystem healthSystem;
    private GateManager gateManager;
    private CachedGateDoor gate;
    private final List<Integer> stateChanges = new ArrayList<>();
    private int asyncPersists;

    @BeforeEach
    void setUp() {
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class))).thenAnswer(inv -> {
            asyncPersists++;
            return mock(BukkitTask.class);
        });
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        gateManager = new GateManager();
        healthSystem = new HealthSystem(mock(GateDoorsApi.class), mock(Plugin.class), null, gateManager);
        gate = new CachedGateDoor(1, 1, "TestGate", "SLIDING", "VERTICAL", "PLANE_GRID",
                60, 1, new Vector(100, 64, 100), 5, 5, 3, 120.0, 500.0, true, false, false, 90, "north");
        gate.setCurrentState(AnimationState.CLOSED);
        gateManager.cacheGate(gate); // fires a state change of its own, so listen from here on
        gateManager.addStateListener(stateChanges::add);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    @Test
    void repairToFullHealsAStandingDoorAndReportsTheHpRestored() {
        assertEquals(380.0, healthSystem.repairToFull(gate));
        assertEquals(500.0, gate.getHealthCurrent());
        assertEquals(List.of(1), stateChanges);
        assertEquals(1, asyncPersists, "the health is persisted");
        assertEquals(AnimationState.CLOSED, gate.getCurrentState(), "no open or close");

        assertEquals(0.0, healthSystem.repairToFull(gate), "already full");
        assertEquals(1, stateChanges.size());
    }

    @Test
    void repairToFullIgnoresDestroyedAndMissingDoors() {
        gate.setIsDestroyed(true);
        gate.setHealthCurrent(0);
        assertEquals(0.0, healthSystem.repairToFull(gate));
        assertEquals(0.0, gate.getHealthCurrent());
        assertEquals(0.0, healthSystem.repairToFull(null));
        assertTrue(stateChanges.isEmpty());
    }

    @Test
    void rebuildForClosingLeavesADestroyedDoorStandingOpenAtTheLastFrameAtFullHealth() {
        gate.setIsDestroyed(true);
        gate.setIsActive(false);
        gate.setIsJammed(true);
        gate.setHealthCurrent(0);
        gate.setRespawnScheduledTime(123L);

        assertTrue(healthSystem.rebuildForClosing(gate));

        assertFalse(gate.isDestroyed());
        assertTrue(gate.isActive());
        assertFalse(gate.isJammed());
        assertEquals(AnimationState.OPEN, gate.getCurrentState());
        assertEquals(60, gate.getCurrentFrame(), "the last frame: the closing animation runs from here to 0");
        assertEquals(500.0, gate.getHealthCurrent());
        assertEquals(0L, gate.getRespawnScheduledTime());
        assertEquals(List.of(1), stateChanges);
        assertEquals(2, asyncPersists, "state and health are persisted");

        // The caller's close now starts the normal animation.
        assertTrue(gateManager.closeGate(gate.getId()));
        assertEquals(AnimationState.CLOSING, gate.getCurrentState());
    }

    @Test
    void rebuildForClosingDoesNothingForAStandingDoor() {
        assertFalse(healthSystem.rebuildForClosing(gate));
        assertFalse(healthSystem.rebuildForClosing(null));
        assertEquals(120.0, gate.getHealthCurrent());
        assertTrue(stateChanges.isEmpty());
        assertEquals(0, asyncPersists);
    }
}
