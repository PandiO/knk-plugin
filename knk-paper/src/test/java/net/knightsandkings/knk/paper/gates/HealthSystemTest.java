package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.api.GateDoorsApi;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HealthSystem.
 * Tests health damage, gate destruction, and respawn mechanics.
 * 
 * NOTE: Some tests require Bukkit runtime and are disabled in unit test environment.
 */
public class HealthSystemTest {

    @Mock
    private GateDoorsApi mockGateDoorsApi;

    @Mock
    private org.bukkit.plugin.java.JavaPlugin mockPlugin;

    private HealthSystem healthSystem;

    @BeforeEach
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        healthSystem = new HealthSystem(mockGateDoorsApi, mockPlugin, null, new GateManager());
    }

    @Test
    @Disabled("Requires Bukkit runtime for BukkitRunnable")
    public void testApplyDamageToVulnerableGate() {
        CachedGateDoor gate = createTestGate();
        gate.setIsInvincible(false);
        gate.setHealthCurrent(100.0);

        healthSystem.applyDamage(gate, 25.0);

        assertEquals(75.0, gate.getHealthCurrent(), 0.1);
    }

    @Test
    @Disabled("Requires Bukkit runtime for BukkitRunnable")
    public void testApplyDamageToInvincibleGate() {
        CachedGateDoor gate = createTestGate();
        gate.setIsInvincible(true);
        gate.setHealthCurrent(100.0);

        healthSystem.applyDamage(gate, 50.0);

        // Invincible gate health should not change
        assertEquals(100.0, gate.getHealthCurrent(), 0.1);
    }

    @Test
    @Disabled("Requires Bukkit runtime for BukkitRunnable")
    public void testApplyDamageMinimumZeroHealth() {
        CachedGateDoor gate = createTestGate();
        gate.setIsInvincible(false);
        gate.setHealthCurrent(10.0);

        healthSystem.applyDamage(gate, 50.0);

        // Health should not go below 0
        assertEquals(0.0, gate.getHealthCurrent(), 0.1);
    }

    @Test
    public void testApplyNegativeDamageIsIgnored() {
        CachedGateDoor gate = createTestGate();
        gate.setHealthCurrent(50.0);

        healthSystem.applyDamage(gate, -10.0);

        assertEquals(50.0, gate.getHealthCurrent(), 0.1);
    }

    @Test
    public void testApplyZeroDamageIsIgnored() {
        CachedGateDoor gate = createTestGate();
        gate.setHealthCurrent(50.0);

        healthSystem.applyDamage(gate, 0.0);

        assertEquals(50.0, gate.getHealthCurrent(), 0.1);
    }

    @Test
    public void testApplyContinuousDamageReducesHealth() {
        // Unlike applyDamage, the non-lethal path never touches BukkitRunnable (no per-tick
        // persistence - see HealthSystem.applyContinuousDamage), so this can run un-Disabled.
        CachedGateDoor gate = createTestGate();
        gate.setIsInvincible(false);
        gate.setHealthCurrent(100.0);

        healthSystem.applyContinuousDamage(gate, 6.0);

        assertEquals(94.0, gate.getHealthCurrent(), 0.1);
    }

    @Test
    public void testApplyContinuousDamageIgnoredWhenInvincible() {
        CachedGateDoor gate = createTestGate();
        gate.setIsInvincible(true);
        gate.setHealthCurrent(100.0);

        healthSystem.applyContinuousDamage(gate, 6.0);

        assertEquals(100.0, gate.getHealthCurrent(), 0.1);
    }

    @Test
    public void testApplyContinuousDamageIgnoredWhenAlreadyDestroyed() {
        CachedGateDoor gate = createTestGate();
        gate.setIsDestroyed(true);
        gate.setHealthCurrent(0.0);

        healthSystem.applyContinuousDamage(gate, 6.0);

        assertEquals(0.0, gate.getHealthCurrent(), 0.1);
    }

    @Test
    public void testApplyContinuousDamageIgnoresNonPositiveAmount() {
        CachedGateDoor gate = createTestGate();
        gate.setHealthCurrent(50.0);

        healthSystem.applyContinuousDamage(gate, 0.0);
        healthSystem.applyContinuousDamage(gate, -5.0);

        assertEquals(50.0, gate.getHealthCurrent(), 0.1);
    }

    @Test
    @Disabled("Requires Bukkit runtime for BukkitRunnable (destroyGate path)")
    public void testApplyContinuousDamageDestroysGateAtZeroHealth() {
        CachedGateDoor gate = createTestGate();
        gate.setIsInvincible(false);
        gate.setHealthCurrent(5.0);

        healthSystem.applyContinuousDamage(gate, 10.0);

        assertTrue(gate.isDestroyed());
        assertEquals(0.0, gate.getHealthCurrent(), 0.1);
    }

    @Test
    @Disabled("Requires Bukkit runtime for BukkitRunnable")
    public void testDestroyGateUpdatesState() {
        CachedGateDoor gate = createTestGate();
        gate.setHealthCurrent(100.0);
        gate.setIsActive(true);
        gate.setIsDestroyed(false);

        // Call destroy without mocking Bukkit.getWorlds() to avoid NPE
        // The destroyGate method will just update the state without actually removing blocks
        healthSystem.destroyGate(gate);

        assertTrue(gate.isDestroyed());
        assertFalse(gate.isActive());
        assertEquals(0.0, gate.getHealthCurrent(), 0.1);
    }

    @Test
    public void testDestroyGateDoesNotDoubleDestroy() {
        CachedGateDoor gate = createTestGate();
        gate.setIsDestroyed(true);

        // Should not throw exception or change state
        healthSystem.destroyGate(gate);

        assertTrue(gate.isDestroyed());
    }

    @Test
    @Disabled("Requires Bukkit runtime for Bukkit.broadcast")
    public void testRespawnGateRestoresHealth() {
        CachedGateDoor gate = createTestGate();
        gate.setIsDestroyed(true);
        gate.setHealthCurrent(0.0);
        gate.setHealthMax(100.0);
        gate.setIsActive(false);

        healthSystem.respawnGate(gate);

        assertFalse(gate.isDestroyed());
        assertTrue(gate.isActive());
        assertEquals(100.0, gate.getHealthCurrent(), 0.1);
    }

    @Test
    public void testRespawnGateOnlyWorksForDestroyedGates() {
        CachedGateDoor gate = createTestGate();
        gate.setIsDestroyed(false);
        gate.setHealthCurrent(50.0);

        healthSystem.respawnGate(gate);

        assertFalse(gate.isDestroyed());
        assertEquals(50.0, gate.getHealthCurrent(), 0.1);
    }

    /**
     * Create a test gate with minimal configuration.
     */
    private CachedGateDoor createTestGate() {
        return new CachedGateDoor(
            1,                              // id
            1,                              // gateStructureId
            "TestGate",                    // name
            "SLIDING",                     // gateType
            "VERTICAL",                    // motionType
            "PLANE_GRID",                  // geometryDefinitionMode
            60,                            // animationDurationTicks
            1,                             // animationTickRate
            new Vector(0, 0, 0),          // anchorPoint
            5,                             // geometryWidth
            5,                             // geometryHeight
            3,                             // geometryDepth
            100.0,                         // healthCurrent
            100.0,                         // healthMax
            true,                          // isActive
            false,                         // isDestroyed
            false,                         // isInvincible
            90,                            // rotationMaxAngleDegrees
            "north"                        // faceDirection
        );
    }
}
