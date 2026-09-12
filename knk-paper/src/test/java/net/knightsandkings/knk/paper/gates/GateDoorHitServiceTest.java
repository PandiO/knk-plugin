package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.paper.events.GateDoorDamageEvent;
import net.knightsandkings.knk.paper.events.GateDoorIgniteEvent;
import net.knightsandkings.knk.paper.events.GateDoorInteractEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GateDoorHitService's gate-state gating and event firing.
 */
class GateDoorHitServiceTest {

    private GateManager gateManager;
    private GateDoorHitService hitService;
    private CachedGateDoor gate;
    private MockedStatic<Bukkit> bukkitMock;
    private PluginManager pluginManager;

    @BeforeEach
    void setUp() {
        gateManager = new GateManager();
        hitService = new GateDoorHitService(gateManager);

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
        gateManager.cacheGate(gate);

        pluginManager = mock(PluginManager.class);
        bukkitMock = mockStatic(Bukkit.class);
        bukkitMock.when(Bukkit::getPluginManager).thenReturn(pluginManager);
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
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(block.getWorld()).thenReturn(world);
        return block;
    }

    @Test
    void resolveDoorGateFindsTheGateAtAnIndexedBlock() {
        Block door = blockAt(100, 64, 100);

        assertSame(gate, hitService.resolveDoorGate("world", door));
    }

    @Test
    void resolveDoorGateReturnsNullForAnUnrelatedBlock() {
        Block unrelated = blockAt(0, 0, 0);

        assertNull(hitService.resolveDoorGate("world", unrelated));
    }

    @Test
    void handleInteractFiresEventWhenGateIsClosedAndActive() {
        Player player = mock(Player.class);
        Block door = blockAt(100, 64, 100);

        GateDoorInteractEvent event = hitService.handleInteract(gate, player, door);

        assertNotNull(event);
        assertSame(gate, event.getGate());
        assertSame(player, event.getPlayer());
        verify(pluginManager).callEvent(event);
    }

    @Test
    void handleInteractDoesNothingWhenGateIsOpen() {
        gate.setCurrentState(AnimationState.OPEN);

        GateDoorInteractEvent event = hitService.handleInteract(gate, mock(Player.class), blockAt(100, 64, 100));

        assertNull(event);
        verify(pluginManager, never()).callEvent(any());
    }

    @Test
    void handleInteractDoesNothingWhenGateIsInactive() {
        gate.setIsActive(false);

        GateDoorInteractEvent event = hitService.handleInteract(gate, mock(Player.class), blockAt(100, 64, 100));

        assertNull(event);
        verify(pluginManager, never()).callEvent(any());
    }

    @Test
    void handleDamageFiresEventWhenGateIsClosedActiveAndNotDestroyed() {
        Entity entity = mock(Entity.class);
        Block door = blockAt(100, 64, 100);

        GateDoorDamageEvent event = hitService.handleDamage(gate, entity, door, GateDoorDamageEvent.Cause.LEFT_CLICK);

        assertNotNull(event);
        assertSame(gate, event.getGate());
        assertEquals(GateDoorDamageEvent.Cause.LEFT_CLICK, event.getCause());
        verify(pluginManager).callEvent(event);
    }

    @Test
    void handleDamageDoesNothingWhenGateIsDestroyed() {
        gate.setIsDestroyed(true);

        GateDoorDamageEvent event = hitService.handleDamage(gate, null, blockAt(100, 64, 100), GateDoorDamageEvent.Cause.EXPLOSION);

        assertNull(event);
        verify(pluginManager, never()).callEvent(any());
    }

    @Test
    void handleDamageDoesNotGateOnInvincibility() {
        // Invincibility is HealthSystem's concern, not the detection service's - it must still fire.
        gate.setIsInvincible(true);

        GateDoorDamageEvent event = hitService.handleDamage(gate, null, blockAt(100, 64, 100), GateDoorDamageEvent.Cause.PROJECTILE);

        assertNotNull(event);
        verify(pluginManager).callEvent(event);
    }

    @Test
    void handleIgniteFiresEventWhenGateIsClosedActiveAndNotDestroyed() {
        Entity entity = mock(Entity.class);
        Block door = blockAt(100, 64, 100);

        GateDoorIgniteEvent event = hitService.handleIgnite(gate, entity, door, GateDoorIgniteEvent.Cause.FLAMING_PROJECTILE);

        assertNotNull(event);
        assertSame(gate, event.getGate());
        assertEquals(GateDoorIgniteEvent.Cause.FLAMING_PROJECTILE, event.getCause());
        verify(pluginManager).callEvent(event);
    }

    @Test
    void handleIgniteDoesNothingWhenGateIsOpen() {
        gate.setCurrentState(AnimationState.OPEN);

        GateDoorIgniteEvent event = hitService.handleIgnite(gate, null, blockAt(100, 64, 100), GateDoorIgniteEvent.Cause.FIRE_CHARGE);

        assertNull(event);
        verify(pluginManager, never()).callEvent(any());
    }

    @Test
    void handleIgniteDoesNothingWhenGateIsDestroyed() {
        gate.setIsDestroyed(true);

        GateDoorIgniteEvent event = hitService.handleIgnite(gate, null, blockAt(100, 64, 100), GateDoorIgniteEvent.Cause.FIRE_CHARGE);

        assertNull(event);
        verify(pluginManager, never()).callEvent(any());
    }

    @Test
    void handleDamageDoesNothingWhenCausingPlayerIsNotInSurvival() {
        Player player = mock(Player.class);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);

        GateDoorDamageEvent event = hitService.handleDamage(gate, player, blockAt(100, 64, 100), GateDoorDamageEvent.Cause.LEFT_CLICK);

        assertNull(event);
        verify(pluginManager, never()).callEvent(any());
    }

    @Test
    void handleDamageFiresEventWhenCausingPlayerIsInSurvival() {
        Player player = mock(Player.class);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);

        GateDoorDamageEvent event = hitService.handleDamage(gate, player, blockAt(100, 64, 100), GateDoorDamageEvent.Cause.LEFT_CLICK);

        assertNotNull(event);
        verify(pluginManager).callEvent(event);
    }

    @Test
    void handleDamageFiresEventWhenCausingEntityIsNotAPlayer() {
        // Gamemode is a player-only concept - a mob-shot arrow always qualifies.
        Entity mob = mock(Entity.class);

        GateDoorDamageEvent event = hitService.handleDamage(gate, mob, blockAt(100, 64, 100), GateDoorDamageEvent.Cause.PROJECTILE);

        assertNotNull(event);
        verify(pluginManager).callEvent(event);
    }

    @Test
    void handleIgniteDoesNothingWhenCausingPlayerIsNotInSurvival() {
        Player player = mock(Player.class);
        when(player.getGameMode()).thenReturn(GameMode.SPECTATOR);

        GateDoorIgniteEvent event = hitService.handleIgnite(gate, player, blockAt(100, 64, 100), GateDoorIgniteEvent.Cause.FLINT_AND_STEEL);

        assertNull(event);
        verify(pluginManager, never()).callEvent(any());
    }

    @Test
    void handleIgniteFiresEventWhenCausingPlayerIsInSurvival() {
        Player player = mock(Player.class);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);

        GateDoorIgniteEvent event = hitService.handleIgnite(gate, player, blockAt(100, 64, 100), GateDoorIgniteEvent.Cause.FLINT_AND_STEEL);

        assertNotNull(event);
        verify(pluginManager).callEvent(event);
    }
}
