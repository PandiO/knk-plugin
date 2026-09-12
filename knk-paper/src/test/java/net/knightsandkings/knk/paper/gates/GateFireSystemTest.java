package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GateFireSystem: ignition bookkeeping and the per-tick damage/prune/extinguish
 * sweep, independent of the BukkitRunnable wrapper (GateFireDamageTask) that drives it in prod.
 */
class GateFireSystemTest {

    private static final long FIRE_DURATION_MILLIS = 8000L;
    private static final double DAMAGE_PER_BLOCK_PER_TICK = 2.0;

    private HealthSystem healthSystem;
    private GateManager gateManager;
    private GateFireSystem fireSystem;
    private CachedGateDoor gate;
    private World world;
    private MockedStatic<Bukkit> bukkitMock;

    @BeforeEach
    void setUp() {
        healthSystem = mock(HealthSystem.class);
        gateManager = new GateManager();
        fireSystem = new GateFireSystem(healthSystem, gateManager, FIRE_DURATION_MILLIS, DAMAGE_PER_BLOCK_PER_TICK);

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
        gateManager.cacheGate(gate);

        world = mock(World.class);
        bukkitMock = mockStatic(Bukkit.class);
        bukkitMock.when(() -> Bukkit.getWorld(anyString())).thenReturn(world);
    }

    @AfterEach
    void tearDown() {
        bukkitMock.close();
    }

    private Block blockAt(int x, int y, int z) {
        Block block = mock(Block.class);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        when(block.getWorld()).thenReturn(world);
        when(block.getLocation()).thenReturn(new Location(world, x, y, z));
        return block;
    }

    @Test
    void igniteBlockRecordsAFutureExpiry() {
        // Block.getWorld() returns null here deliberately: referencing org.bukkit.Sound (used
        // for the ignite sound effect on the world != null branch) triggers Sound's registry-
        // backed enum <clinit>, which needs a live Paper server - see
        // igniteBlockSpawnsEffectsAtTheBlock below. This test isolates the bookkeeping, which has
        // no such dependency.
        Block door = mock(Block.class);
        when(door.getX()).thenReturn(100);
        when(door.getY()).thenReturn(64);
        when(door.getZ()).thenReturn(100);
        when(door.getWorld()).thenReturn(null);
        long before = System.currentTimeMillis();

        fireSystem.igniteBlock(gate, door);

        Vector position = new Vector(100, 64, 100);
        assertTrue(gate.isOnFire());
        Long expiry = gate.getBurningBlocks().get(position);
        assertNotNull(expiry);
        assertTrue(expiry >= before + FIRE_DURATION_MILLIS);
    }

    @Test
    @Disabled("Sound is a registry-backed enum that needs a live Paper server to initialize")
    void igniteBlockSpawnsEffectsAtTheBlock() {
        Block door = blockAt(100, 64, 100);

        fireSystem.igniteBlock(gate, door);

        verify(world).spawnParticle(eq(org.bukkit.Particle.FLAME), anyDouble(), anyDouble(), anyDouble(),
            anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
        verify(world).playSound(any(Location.class), eq(org.bukkit.Sound.ITEM_FIRECHARGE_USE), any(), anyFloat(), anyFloat());
    }

    @Test
    void igniteBlockIgnoresNullGateOrBlock() {
        fireSystem.igniteBlock(null, blockAt(1, 2, 3));
        fireSystem.igniteBlock(gate, null);

        assertFalse(gate.isOnFire());
        verifyNoInteractions(world);
    }

    @Test
    void tickDoesNothingForGatesWithNoBurningBlocks() {
        fireSystem.tick();

        verifyNoInteractions(healthSystem);
    }

    @Test
    void tickAppliesDamagePerStillBurningBlockAndPrunesExpired() {
        long now = System.currentTimeMillis();
        gate.getBurningBlocks().put(new Vector(100, 64, 100), now + 5000L); // still burning
        gate.getBurningBlocks().put(new Vector(101, 64, 100), now - 1L);    // just expired

        fireSystem.tick();

        assertEquals(1, gate.getBurningBlocks().size());
        assertTrue(gate.getBurningBlocks().containsKey(new Vector(100, 64, 100)));
        verify(healthSystem).applyContinuousDamage(gate, DAMAGE_PER_BLOCK_PER_TICK * 1);
    }

    @Test
    void tickStacksDamageAcrossMultipleBurningBlocks() {
        long now = System.currentTimeMillis();
        gate.getBurningBlocks().put(new Vector(100, 64, 100), now + 5000L);
        gate.getBurningBlocks().put(new Vector(101, 64, 100), now + 5000L);

        fireSystem.tick();

        verify(healthSystem).applyContinuousDamage(gate, DAMAGE_PER_BLOCK_PER_TICK * 2);
    }

    @Test
    void tickExtinguishesWhenGateIsNoLongerClosed() {
        gate.getBurningBlocks().put(new Vector(100, 64, 100), System.currentTimeMillis() + 5000L);
        gate.setCurrentState(AnimationState.OPENING);

        fireSystem.tick();

        assertFalse(gate.isOnFire());
        verifyNoInteractions(healthSystem);
    }

    @Test
    void tickExtinguishesWhenGateIsDestroyed() {
        gate.getBurningBlocks().put(new Vector(100, 64, 100), System.currentTimeMillis() + 5000L);
        gate.setIsDestroyed(true);

        fireSystem.tick();

        assertFalse(gate.isOnFire());
        verifyNoInteractions(healthSystem);
    }

    @Test
    void tickClearsBurningBlocksWhenDamageDestroysTheGate() {
        gate.getBurningBlocks().put(new Vector(100, 64, 100), System.currentTimeMillis() + 5000L);
        doAnswer(invocation -> {
            CachedGateDoor target = invocation.getArgument(0);
            target.setIsDestroyed(true);
            return null;
        }).when(healthSystem).applyContinuousDamage(eq(gate), anyDouble());

        fireSystem.tick();

        assertFalse(gate.isOnFire());
    }
}
