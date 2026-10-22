package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.paper.events.GateDoorDamageEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * GateAreaDamageService: chaos / flash_chaos hurt every gate with a door block in range, once each, with the
 * enchantment's own damage.
 */
class GateAreaDamageServiceTest {

    private GateManager gateManager;
    private GateAreaDamageService service;
    private CachedGateDoor gate;
    private World world;
    private Player caster;
    private PluginManager pluginManager;
    private MockedStatic<Bukkit> bukkitMock;

    @BeforeEach
    void setUp() {
        gateManager = new GateManager();
        service = new GateAreaDamageService(gateManager, new GateDoorHitService(gateManager));

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
        when(world.getName()).thenReturn("world");
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
            Block block = mock(Block.class);
            when(block.getX()).thenReturn(invocation.getArgument(0));
            when(block.getY()).thenReturn(invocation.getArgument(1));
            when(block.getZ()).thenReturn(invocation.getArgument(2));
            return block;
        });

        caster = mock(Player.class);
        when(caster.getGameMode()).thenReturn(GameMode.SURVIVAL);

        pluginManager = mock(PluginManager.class);
        bukkitMock = mockStatic(Bukkit.class);
        bukkitMock.when(Bukkit::getPluginManager).thenReturn(pluginManager);
    }

    @AfterEach
    void tearDown() {
        bukkitMock.close();
    }

    private List<GateDoorDamageEvent> damageEvents() {
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(pluginManager, atLeast(0)).callEvent(captor.capture());
        return captor.getAllValues().stream()
            .filter(GateDoorDamageEvent.class::isInstance)
            .map(GateDoorDamageEvent.class::cast)
            .toList();
    }

    @Test
    void aGateInRangeTakesTheEnchantmentsOwnDamageOnce() {
        // the caster stands next to the gate; both of its door blocks are in range
        service.damageGatesAround(caster, new Location(world, 100.5, 64, 98.5), 3.0, 60.0);

        List<GateDoorDamageEvent> events = damageEvents();
        assertEquals(1, events.size());
        GateDoorDamageEvent event = events.get(0);
        assertSame(gate, event.getGate());
        assertEquals(GateDoorDamageEvent.Cause.ENCHANTMENT, event.getCause());
        assertEquals(60.0, event.getDamage());
        assertSame(caster, event.getCausingEntity());
    }

    @Test
    void theHitIsReportedOnTheDoorBlockNearestTheCaster() {
        service.damageGatesAround(caster, new Location(world, 101.5, 64, 98.5), 3.0, 40.0);

        Block hit = damageEvents().get(0).getHitBlock();
        assertEquals(101, hit.getX());
        assertEquals(64, hit.getY());
        assertEquals(100, hit.getZ());
    }

    @Test
    void aGateOutOfRangeIsLeftAlone() {
        service.damageGatesAround(caster, new Location(world, 100.5, 64, 90.5), 3.0, 60.0);

        assertTrue(damageEvents().isEmpty());
    }

    @Test
    void theRangeIsMeasuredToTheNearestEdgeOfABlock() {
        // 3.5 blocks from the block centre but exactly 3 from its face: the cube's edge counts
        Location center = new Location(world, 100.5, 64.5, 97.0);
        assertEquals(3.0, GateAreaDamageService.distanceToBlock(center, 100, 64, 100), 1e-9);

        service.damageGatesAround(caster, center, 3.0, 40.0);
        assertEquals(1, damageEvents().size());
    }

    @Test
    void aGateThatIsNotClosedIsLeftAlone() {
        gate.setCurrentState(AnimationState.OPENING);

        service.damageGatesAround(caster, new Location(world, 100.5, 64, 98.5), 3.0, 60.0);

        assertTrue(damageEvents().isEmpty());
    }

    @Test
    void aCreativeCasterDoesNoDamage() {
        when(caster.getGameMode()).thenReturn(GameMode.CREATIVE);

        service.damageGatesAround(caster, new Location(world, 100.5, 64, 98.5), 3.0, 60.0);

        assertTrue(damageEvents().isEmpty());
    }

    @Test
    void nothingHappensWithoutAWorldOrDamage() {
        service.damageGatesAround(caster, new Location(null, 100.5, 64, 98.5), 3.0, 60.0);
        service.damageGatesAround(caster, new Location(world, 100.5, 64, 98.5), 3.0, 0.0);
        service.damageGatesAround(caster, new Location(world, 100.5, 64, 98.5), 0.0, 60.0);
        service.damageGatesAround(caster, null, 3.0, 60.0);

        assertTrue(damageEvents().isEmpty());
    }
}
