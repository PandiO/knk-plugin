package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.siege.SiegeGatePlan.Control;
import net.knightsandkings.knk.paper.events.GateDoorDamageEvent;
import net.knightsandkings.knk.paper.events.GateDoorIgniteEvent;
import net.knightsandkings.knk.paper.events.GateDoorInteractEvent;
import net.knightsandkings.knk.paper.siege.SiegeGateController;
import net.knightsandkings.knk.paper.siege.SiegeMessages;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Siege Phase 7a (DESIGN §8.3): gates locked down by a match. Right-clicking a closed locked gate
 * ({@link GateDoorInteractEvent}) opens it and right-clicking an open one closes it - only for the
 * owner team's alliance; the gate's own pass-through never runs. Damage ({@link GateDoorDamageEvent},
 * fire via {@link GateDoorIgniteEvent}) counts only from an enemy of the owner on a damageable selected
 * gate. Runs at LOWEST so the gate package's consequence listeners see the cancellation.
 */
public final class SiegeGateListener implements Listener {

    private final SiegeGateController gates;
    private final GateManager gateManager;

    public SiegeGateListener(SiegeGateController gates, GateManager gateManager) {
        this.gates = gates;
        this.gateManager = gateManager;
    }

    /** Fired by the gate package for right-clicks on a CLOSED door. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onGateInteract(GateDoorInteractEvent event) {
        int structureId = event.getGate().getGateStructureId();
        if (!gates.isLocked(structureId)) return;
        event.setCancelled(true);
        // Phase 7b: a non-member at a gate that was open before the lockdown is carried across.
        if (gates.tryNonMemberPassThrough(event.getPlayer(), event.getGate())) return;
        respond(event.getPlayer(), structureId, gates.control(event.getPlayer(), structureId, true));
    }

    /** The gate package fires nothing for OPEN doors, so closing is handled here. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRightClickOpenGate(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        Integer doorId = gateManager.getSpatialIndex().lookup(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        if (doorId == null) return;
        CachedGateDoor door = gateManager.getGate(doorId);
        if (door == null || door.getCurrentState() != AnimationState.OPEN) return;
        int structureId = door.getGateStructureId();
        if (!gates.isLocked(structureId)) return;
        event.setCancelled(true);
        respond(event.getPlayer(), structureId, gates.control(event.getPlayer(), structureId, false));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onGateDamage(GateDoorDamageEvent event) {
        int structureId = event.getGate().getGateStructureId();
        if (gates.isLocked(structureId) && !gates.mayDamage(attacker(event.getCausingEntity()), structureId)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onGateIgnite(GateDoorIgniteEvent event) {
        int structureId = event.getGate().getGateStructureId();
        if (gates.isLocked(structureId) && !gates.mayDamage(attacker(event.getCausingEntity()), structureId)) {
            event.setCancelled(true);
        }
    }

    private void respond(Player player, int structureId, Control decision) {
        String siege = gates.lobbyNameOf(structureId).orElse("a siege");
        switch (decision) {
            case ALLOWED, NOT_IN_PLAN -> { }
            case NOT_OWNER -> player.sendActionBar(SiegeMessages.bad("This gate is held by the enemy."));
            case NOT_IN_MATCH -> player.sendActionBar(SiegeMessages.bad("This gate is part of the siege " + siege + "."));
            case FORCED_OPEN -> player.sendActionBar(SiegeMessages.bad("This gate is held open during the siege " + siege + "."));
        }
    }

    /** The player behind a hit: the player, or the shooter of a projectile; null otherwise. */
    private static Player attacker(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) return shooter;
        return null;
    }
}
