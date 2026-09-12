package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.paper.events.GateDoorDamageEvent;
import net.knightsandkings.knk.paper.events.GateDoorIgniteEvent;
import net.knightsandkings.knk.paper.events.GateDoorInteractEvent;
import net.knightsandkings.knk.paper.gates.GateDoorHitService;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Adapts raw Bukkit events (block break, explosions, clicks, projectile hits) into
 * GateDoorHitService lookups, translating an O(1) spatial-index resolution into the
 * GateDoorInteractEvent / GateDoorDamageEvent custom events other systems react to.
 * Detection lives in GateDoorHitService; this class only wires Bukkit events to it and
 * propagates any resulting cancellation back onto the originating event.
 */
public class GateEventListener implements Listener {
    private static final Logger LOGGER = Logger.getLogger(GateEventListener.class.getName());

    private static final BlockFace[] ADJACENT_FACES = {
        BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final GateDoorHitService hitService;

    public GateEventListener(GateDoorHitService hitService) {
        this.hitService = hitService;
    }

    /**
     * Handle block break attempts on gate blocks.
     * Prevents breaking gate blocks unless player has admin permission, and registers damage
     * on the gate for a completed break attempt (in addition to left-click punch damage - see
     * onPlayerInteract - both are kept as independent damage sources).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();

        CachedGateDoor gate = hitService.resolveDoorGate(block.getWorld().getName(), block);
        if (gate == null) {
            return;
        }

        // Allow admins to break gate blocks
        if (player.hasPermission("knk.gate.admin")) {
            LOGGER.info("Admin " + player.getName() + " broke gate block at " + block.getLocation());
            return;
        }

        // Prevent breaking gate blocks
        event.setCancelled(true);
        player.sendMessage(
            Component.text("You cannot break gate blocks. Gate: " + gate.getName())
                .color(NamedTextColor.RED)
        );

        hitService.handleDamage(gate, player, block, GateDoorDamageEvent.Cause.BLOCK_BREAK);
    }

    /**
     * Handle explosions caused by an entity (creeper, TNT primed by an entity, etc.) that might
     * hit gate blocks. Gate blocks are always protected from vanilla destruction; damage is
     * registered separately via GateDoorDamageEvent.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Set<Block> blocksToRemove = new HashSet<>();

        for (Block block : event.blockList()) {
            CachedGateDoor gate = hitService.resolveDoorGate(block.getWorld().getName(), block);
            if (gate == null) {
                continue;
            }

            blocksToRemove.add(block);
            hitService.handleDamage(gate, event.getEntity(), block, GateDoorDamageEvent.Cause.EXPLOSION);
        }

        event.blockList().removeAll(blocksToRemove);
    }

    /**
     * Handle explosions with no causing entity (e.g. a bed/respawn-anchor detonation).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        Set<Block> blocksToRemove = new HashSet<>();

        for (Block block : event.blockList()) {
            CachedGateDoor gate = hitService.resolveDoorGate(block.getWorld().getName(), block);
            if (gate == null) {
                continue;
            }

            blocksToRemove.add(block);
            hitService.handleDamage(gate, null, block, GateDoorDamageEvent.Cause.EXPLOSION);
        }

        event.blockList().removeAll(blocksToRemove);
    }

    /**
     * Handle an arrow or other projectile hitting a gate's door block. A projectile that is
     * itself on fire (e.g. an arrow shot with the Flame enchantment, or a fire charge's
     * SmallFireball) additionally sets the door block alight, on top of the normal PROJECTILE
     * impact damage - see GateFireSystem for the resulting damage-over-time.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Block hitBlock = event.getHitBlock();
        if (hitBlock == null) {
            // Hit an entity instead of a block - not relevant to gate doors.
            return;
        }

        CachedGateDoor gate = hitService.resolveDoorGate(hitBlock.getWorld().getName(), hitBlock);
        if (gate == null) {
            return;
        }

        Entity shooter = event.getEntity() instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooterEntity
            ? shooterEntity
            : null;

        hitService.handleDamage(gate, shooter, hitBlock, GateDoorDamageEvent.Cause.PROJECTILE);

        GateDoorIgniteEvent.Cause igniteCause = resolveIgniteCause(event.getEntity());
        if (igniteCause != null) {
            hitService.handleIgnite(gate, shooter, hitBlock, igniteCause);
        }
    }

    /**
     * Determine whether a projectile that just hit a gate door block should set it on fire:
     * a fire charge (SmallFireball) always does, and any other projectile counts if it is
     * currently burning (Entity.getFireTicks() > 0 - true for an arrow shot with Flame, or one
     * that flew through fire/lava en route).
     */
    private static GateDoorIgniteEvent.Cause resolveIgniteCause(Entity projectileEntity) {
        if (projectileEntity instanceof Fireball) {
            return GateDoorIgniteEvent.Cause.FIRE_CHARGE;
        }
        if (projectileEntity != null && projectileEntity.getFireTicks() > 0) {
            return GateDoorIgniteEvent.Cause.FLAMING_PROJECTILE;
        }
        return null;
    }

    /**
     * Handle a block catching fire from flint and steel or a fireball's block-ignite (as
     * opposed to a direct ProjectileHitEvent hit - see onProjectileHit). Vanilla ignition
     * targets a face: flint and steel used against a gate door block sets fire on the adjacent
     * AIR block the player was looking at, not the door block itself, and a fireball's own
     * BlockIgniteEvent behaves the same way. Left alone, that leaves a cosmetic fire block
     * floating next to the gate instead of the gate actually burning - so this cancels that
     * vanilla placement and, once the door block responsible is found (either the ignited block
     * itself, for a directly-flammable gate material, or one of its 6 neighbors), ignites that
     * door block for real via GateFireSystem instead.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        GateDoorIgniteEvent.Cause cause = resolveIgniteCause(event.getCause());
        if (cause == null) {
            return;
        }

        Block ignitedBlock = event.getBlock();
        String worldName = ignitedBlock.getWorld().getName();

        CachedGateDoor directGate = hitService.resolveDoorGate(worldName, ignitedBlock);
        if (directGate != null) {
            event.setCancelled(true);
            hitService.handleIgnite(directGate, event.getPlayer(), ignitedBlock, cause);
            return;
        }

        for (BlockFace face : ADJACENT_FACES) {
            Block neighbor = ignitedBlock.getRelative(face);
            CachedGateDoor gate = hitService.resolveDoorGate(worldName, neighbor);
            if (gate == null) {
                continue;
            }

            event.setCancelled(true);
            hitService.handleIgnite(gate, event.getPlayer(), neighbor, cause);
            return;
        }
    }

    /**
     * Map a BlockIgniteEvent cause to the corresponding GateDoorIgniteEvent cause, or null if
     * this cause shouldn't trigger gate-block ignition (e.g. lava contact, fire spread, or
     * lightning - none of those are a deliberate "set this gate on fire" action).
     */
    private static GateDoorIgniteEvent.Cause resolveIgniteCause(BlockIgniteEvent.IgniteCause cause) {
        return switch (cause) {
            case FLINT_AND_STEEL -> GateDoorIgniteEvent.Cause.FLINT_AND_STEEL;
            case FIREBALL -> GateDoorIgniteEvent.Cause.FIRE_CHARGE;
            default -> null;
        };
    }

    /**
     * Handle player interact events on gate door blocks: right-click fires GateDoorInteractEvent
     * (consumed by GatePassThroughConsequenceListener, which owns the pass-through permission
     * check - knk.gate.open and knk.gate.close are for the manual /knk gate open|close commands
     * and are unrelated to pass-through, so they no longer gate detection here), left-click punch
     * as a melee damage source.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) {
            return;
        }

        CachedGateDoor gate = hitService.resolveDoorGate(clickedBlock.getWorld().getName(), clickedBlock);
        if (gate == null) {
            return;
        }

        Player player = event.getPlayer();

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            GateDoorInteractEvent interactEvent = hitService.handleInteract(gate, player, clickedBlock);
            if (interactEvent != null && interactEvent.isCancelled()) {
                event.setCancelled(true);
            }
        } else if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            hitService.handleDamage(gate, player, clickedBlock, GateDoorDamageEvent.Cause.LEFT_CLICK);
        }
    }
}
