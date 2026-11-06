package net.knightsandkings.knk.paper.npc;

import com.destroystokyo.paper.entity.Pathfinder;
import net.knightsandkings.knk.core.npc.NpcStatPipeline;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The melee goal: active with a target, chases, attacks in reach only when the pipeline cooldown allows. */
class NpcMeleeGoalTest {

    private final World world = mock(World.class);
    private final Plugin plugin = mock(Plugin.class);
    private final FakeMobAttributes attributes = new FakeMobAttributes();
    private long tick = 100;
    private final NpcPlatform platform;
    private final Zombie zombie = mock(Zombie.class);
    private final Player target = mock(Player.class);
    private final Pathfinder pathfinder = mock(Pathfinder.class);

    NpcMeleeGoalTest() {
        when(plugin.getName()).thenReturn("knk");
        when(plugin.namespace()).thenReturn("knk");
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        platform = new NpcPlatform(plugin, NpcStatPipeline.vanilla(), attributes, null, () -> tick, 96);
        when(zombie.getUniqueId()).thenReturn(UUID.randomUUID());
        when(zombie.getType()).thenReturn(EntityType.ZOMBIE);
        when(zombie.isValid()).thenReturn(true);
        when(zombie.getWidth()).thenReturn(0.6);
        when(zombie.getPathfinder()).thenReturn(pathfinder);
        when(zombie.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(target.isValid()).thenReturn(true);
        when(target.getWidth()).thenReturn(0.6);
        when(target.getLocation()).thenReturn(new Location(world, 1, 64, 0));
        attributes.base.put(NpcAttribute.ATTACK_DAMAGE, 3.0);
        platform.adopt(zombie, "zombie");
    }

    @Test
    void inactiveWithoutATargetAndAttacksOnTheCooldown() {
        NpcMeleeGoal goal = new NpcMeleeGoal(platform, zombie);
        assertFalse(goal.shouldActivate());
        when(zombie.getTarget()).thenReturn(target);
        assertTrue(goal.shouldActivate());
        goal.start();
        goal.tick();
        verify(pathfinder, times(1)).moveTo(target, 1.0);
        verify(zombie, times(1)).attack(target);
        verify(zombie).swingMainHand();
        tick = 110; // vanilla cooldown 20 ticks: not yet
        goal.tick();
        verify(zombie, times(1)).attack(target);
        tick = 120;
        goal.tick();
        verify(zombie, times(2)).attack(target);
    }

    @Test
    void outOfReachOnlyChases() {
        when(zombie.getTarget()).thenReturn(target);
        when(target.getLocation()).thenReturn(new Location(world, 6, 64, 0));
        NpcMeleeGoal goal = new NpcMeleeGoal(platform, zombie);
        goal.start();
        goal.tick();
        verify(pathfinder).moveTo(target, 1.0);
        verify(zombie, never()).attack(target);
        goal.stop();
        verify(zombie).setAggressive(false);
    }
}
