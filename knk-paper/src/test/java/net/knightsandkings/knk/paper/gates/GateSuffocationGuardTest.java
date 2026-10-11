package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.gates.GateSpatialIndex;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** KNG-106: no suffocation damage from gate door blocks; the entity is moved out instead. */
class GateSuffocationGuardTest {
    private final World world = mock(World.class);
    private GateManager gateManager;
    private GateSpatialIndex index;
    private Entity entity;

    @BeforeEach
    void setUp() {
        when(world.getName()).thenReturn("world");
        gateManager = mock(GateManager.class);
        index = new GateSpatialIndex();
        when(gateManager.getSpatialIndex()).thenReturn(index);
        entity = mock(Entity.class);
        when(entity.getWorld()).thenReturn(world);
        // a player whose head is in block (1, 65, 0)
        when(entity.getBoundingBox()).thenReturn(new BoundingBox(1.2, 64, 0.2, 1.8, 65.8, 0.8));
    }

    private EntityDamageEvent damage(DamageCause cause) {
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getCause()).thenReturn(cause);
        when(event.getEntity()).thenReturn(entity);
        return event;
    }

    @Test
    void suffocatingInADoorBlockIsCancelled() {
        index.put("world", new Vector(1, 65, 0), 16);
        EntityDamageEvent event = damage(DamageCause.SUFFOCATION);

        new GateSuffocationGuard(mock(Plugin.class), gateManager, false).onDamage(event);

        verify(event).setCancelled(true);
        verify(gateManager).getGate(16);
    }

    @Test
    void suffocatingInAnOrdinaryBlockIsLeftAlone() {
        EntityDamageEvent event = damage(DamageCause.SUFFOCATION);

        new GateSuffocationGuard(mock(Plugin.class), gateManager, false).onDamage(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    void otherDamageInADoorIsLeftAlone() {
        index.put("world", new Vector(1, 65, 0), 16);
        EntityDamageEvent event = damage(DamageCause.FIRE);

        new GateSuffocationGuard(mock(Plugin.class), gateManager, false).onDamage(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    void theDamageStaysWhenConfiguredButTheEntityIsStillMovedOut() {
        index.put("world", new Vector(1, 65, 0), 16);
        EntityDamageEvent event = damage(DamageCause.SUFFOCATION);

        new GateSuffocationGuard(mock(Plugin.class), gateManager, true).onDamage(event);

        verify(event, never()).setCancelled(true);
        verify(gateManager).getGate(16);
    }

    @Test
    void gateInsideFindsADoorBlockAnywhereInTheBox() {
        GateSuffocationGuard guard = new GateSuffocationGuard(mock(Plugin.class), gateManager, false);

        assertNull(guard.gateInside(entity));
        index.put("world", new Vector(1, 64, 0), 17);
        assertEquals(17, guard.gateInside(entity));
    }

    @Test
    void touchingADoorFaceIsNotInside() {
        index.put("world", new Vector(1, 65, 1), 16);
        when(entity.getBoundingBox()).thenReturn(new BoundingBox(1.2, 64, 0.4, 1.8, 65.8, 1.0));

        assertNull(new GateSuffocationGuard(mock(Plugin.class), gateManager, false).gateInside(entity));
    }
}
