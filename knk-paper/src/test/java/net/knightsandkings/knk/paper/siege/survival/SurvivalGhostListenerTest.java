package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.visibility.GhostTeam;
import net.knightsandkings.knk.paper.visibility.PlayerVisibilityArbiter;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DESIGN §3b: the ghost cancellations, and the S60 confinement; a living player is untouched. */
class SurvivalGhostListenerTest {

    private final World world = mock(World.class);
    private final UUID ghostId = UUID.randomUUID();
    private final Player ghost = player(ghostId);
    private final Player living = player(UUID.randomUUID());
    private final Location spawn = new Location(world, 0, 64, 0);
    private final SurvivalGhosts registry = new SurvivalGhosts(mock(PlayerVisibilityArbiter.class), new GhostTeam("t", id -> true), p -> Optional.of(spawn));
    private final ScenarioArea area = loc -> loc.getX() < 100;
    private final SurvivalGhostListener listener = new SurvivalGhostListener(new SurvivalGhostListener.Ghosts() {
        @Override
        public boolean isGhost(UUID playerId) {
            return Set.of(ghostId).contains(playerId);
        }

        @Override
        public Optional<SurvivalGhostListener.GhostContext> contextOf(UUID playerId) {
            return isGhost(playerId) ? Optional.of(new SurvivalGhostListener.GhostContext(registry, area, p -> Optional.of(spawn))) : Optional.empty();
        }
    });

    private Player player(UUID id) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(id);
        when(p.getFoodLevel()).thenReturn(20);
        return p;
    }

    private static EntityDamageByEntityEvent damage(org.bukkit.entity.Entity damager, org.bukkit.entity.Entity victim) {
        // The real constructor needs the server's damage-type registry; a mock carries the two entities.
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(damager);
        when(event.getEntity()).thenReturn(victim);
        return event;
    }

    @Test
    void ghostsTakeNoDamageAndDealNone() {
        Zombie zombie = mock(Zombie.class);
        EntityDamageByEntityEvent hit = damage(zombie, ghost);
        listener.onDamageByEntity(hit);
        verify(hit).setCancelled(true);
        EntityDamageByEntityEvent swing = damage(ghost, zombie);
        listener.onDamageByEntity(swing);
        verify(swing).setCancelled(true);
        EntityDamageByEntityEvent ok = damage(living, zombie);
        listener.onDamageByEntity(ok);
        verify(ok, never()).setCancelled(true);
        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(ghost);
        EntityDamageByEntityEvent shot = damage(arrow, zombie);
        listener.onDamageByEntity(shot);
        verify(shot).setCancelled(true);
        EntityDamageEvent plain = mock(EntityDamageEvent.class);
        when(plain.getEntity()).thenReturn(ghost);
        listener.onDamage(plain);
        verify(plain).setCancelled(true);
    }

    @Test
    void hungerTargetingProjectilesAndDropsAreBlocked() {
        FoodLevelChangeEvent hunger = new FoodLevelChangeEvent(ghost, 19);
        listener.onHunger(hunger);
        assertTrue(hunger.isCancelled());
        FoodLevelChangeEvent eat = new FoodLevelChangeEvent(ghost, 20);
        listener.onHunger(eat);
        assertFalse(eat.isCancelled(), "gaining food is fine");

        Zombie zombie = mock(Zombie.class);
        EntityTargetLivingEntityEvent target = new EntityTargetLivingEntityEvent(zombie, ghost, EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
        listener.onTarget(target);
        assertTrue(target.isCancelled());
        EntityTargetLivingEntityEvent fine = new EntityTargetLivingEntityEvent(zombie, living, EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
        listener.onTarget(fine);
        assertFalse(fine.isCancelled());

        Arrow arrow = mock(Arrow.class);
        ProjectileHitEvent hit = new ProjectileHitEvent(arrow, ghost, null, null);
        listener.onProjectileHit(hit);
        assertTrue(hit.isCancelled());

        org.bukkit.entity.Item item = mock(org.bukkit.entity.Item.class);
        when(item.getItemStack()).thenReturn(mock(ItemStack.class));
        PlayerDropItemEvent drop = new PlayerDropItemEvent(ghost, item);
        listener.onDrop(drop);
        assertTrue(drop.isCancelled());
        PlayerDropItemEvent livingDrop = new PlayerDropItemEvent(living, item);
        listener.onDrop(livingDrop);
        assertFalse(livingDrop.isCancelled());
    }

    @Test
    void aGhostLeavingTheAreaIsSentBackToASpawn() {
        PlayerMoveEvent inside = new PlayerMoveEvent(ghost, new Location(world, 50, 64, 0), new Location(world, 51, 64, 0));
        listener.onMove(inside);
        assertFalse(inside.isCancelled());
        verify(ghost, never()).teleport(spawn);

        PlayerMoveEvent outside = new PlayerMoveEvent(ghost, new Location(world, 99, 64, 0), new Location(world, 101, 64, 0));
        listener.onMove(outside);
        assertTrue(outside.isCancelled());
        verify(ghost).teleport(spawn);

        PlayerMoveEvent livingOutside = new PlayerMoveEvent(living, new Location(world, 99, 64, 0), new Location(world, 101, 64, 0));
        listener.onMove(livingOutside);
        assertFalse(livingOutside.isCancelled());
    }
}
