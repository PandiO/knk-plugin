package net.knightsandkings.knk.paper.gates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.api.GateDoorsApi;

/**
 * KNG-34 (DESIGN.md §F.8, L1-12): {@code applyDamage}/{@code applyContinuousDamage} return the effective
 * HP lost - {@code before - max(0, before - amount)} - and 0 whenever nothing is applied; the HP outcome
 * is the one the methods always produced. The scheduler is mocked so the non-lethal {@code applyDamage}
 * path (async persist) runs without a server.
 */
class HealthSystemEffectiveLossTest {

    private MockedStatic<Bukkit> bukkit;
    private HealthSystem healthSystem;
    private CachedGateDoor gate;

    @BeforeEach
    void setUp() {
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class))).thenReturn(task);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        healthSystem = new HealthSystem(mock(GateDoorsApi.class), mock(Plugin.class), null, new GateManager());
        gate = new CachedGateDoor(
            1, 1, "TestGate", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(100, 64, 100), 5, 5, 3,
            100.0, 500.0, true, false, false, 90, "north"
        );
        gate.setCurrentState(AnimationState.CLOSED);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    @Test
    void aDirectHitReturnsTheHpItTook() {
        assertEquals(10.0, healthSystem.applyDamage(gate, 10.0));
        assertEquals(90.0, gate.getHealthCurrent());
        assertEquals(2.5, healthSystem.applyDamage(gate, 2.5));
        assertEquals(87.5, gate.getHealthCurrent());
    }

    @Test
    void continuousDamageReturnsTheHpItTookCappedByWhatWasLeft() {
        assertEquals(6.0, healthSystem.applyContinuousDamage(gate, 6.0));
        assertEquals(94.0, gate.getHealthCurrent());
        gate.setHealthCurrent(3.0);
        // the lethal path (destroyGate) needs a server; the cap is visible one step before it
        assertEquals(2.0, healthSystem.applyContinuousDamage(gate, 2.0));
        assertEquals(1.0, gate.getHealthCurrent());
    }

    @Test
    void nothingAppliedReturnsZero() {
        assertEquals(0.0, healthSystem.applyDamage(null, 10.0));
        assertEquals(0.0, healthSystem.applyDamage(gate, 0.0));
        assertEquals(0.0, healthSystem.applyDamage(gate, -5.0));
        assertEquals(0.0, healthSystem.applyContinuousDamage(gate, 0.0));
        assertEquals(0.0, healthSystem.applyContinuousDamage(null, 3.0));

        gate.setIsInvincible(true);
        assertEquals(0.0, healthSystem.applyDamage(gate, 10.0), "invincible");
        assertEquals(0.0, healthSystem.applyContinuousDamage(gate, 10.0), "invincible");
        assertEquals(100.0, gate.getHealthCurrent());

        gate.setIsInvincible(false);
        gate.setIsDestroyed(true);
        assertEquals(0.0, healthSystem.applyDamage(gate, 10.0), "destroyed");
        assertEquals(0.0, healthSystem.applyContinuousDamage(gate, 10.0), "destroyed");
        assertEquals(100.0, gate.getHealthCurrent());
    }
}
