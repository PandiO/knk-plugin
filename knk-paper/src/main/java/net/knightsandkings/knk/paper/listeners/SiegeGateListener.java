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

import java.util.function.BiConsumer;

/**
 * Siege Phase 7a (DESIGN §8.3): gates locked down by a match. Right-clicking a closed locked gate
 * ({@link GateDoorInteractEvent}) lets the owner team's alliance pass through: it opens and closes again by
 * itself (alpha run 1, PandiO/knk-workspace#154; with {@code gates.siege.defender-pass-through: false} it
 * stays open, the MVP toggle). Right-clicking an open one extends such a pass-through, or closes a gate that
 * stands open for another reason. The gate's own pass-through never runs. Damage ({@link GateDoorDamageEvent},
 * fire via {@link GateDoorIgniteEvent}) counts only from an enemy of the owner on a damageable selected
 * gate. Runs at LOWEST so the gate package's consequence listeners see the cancellation. Non-members' clicks go to
 * the non-member gate view: they click the door as it was before the match (DESIGN §8.5, PandiO/knk-workspace#213).
 */
public final class SiegeGateListener implements Listener {

    private final SiegeGateController gates;
    private final GateManager gateManager;
    /** #213: handles a non-member's click on a locked door (their pre-game view); null until wired. */
    private BiConsumer<Player, CachedGateDoor> nonMemberClicks;

    public SiegeGateListener(SiegeGateController gates, GateManager gateManager) {
        this.gates = gates;
        this.gateManager = gateManager;
    }

    /** #213: the siege non-member gate view handles non-members' clicks on locked doors. */
    public void setNonMemberClicks(BiConsumer<Player, CachedGateDoor> nonMemberClicks) {
        this.nonMemberClicks = nonMemberClicks;
    }

    /** Fired by the gate package for right-clicks on a CLOSED door. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onGateInteract(GateDoorInteractEvent event) {
        int structureId = event.getGate().getGateStructureId();
        if (!gates.isLocked(structureId)) return;
        event.setCancelled(true);
        // #213: a non-member clicks the door they see - its pre-game state (carried across, or refused).
        if (!gates.isLockdownMember(event.getPlayer(), structureId)) {
            if (nonMemberClicks != null) nonMemberClicks.accept(event.getPlayer(), event.getGate());
            return;
        }
        respond(event.getPlayer(), structureId, gates.openClosedGate(event.getPlayer(), structureId));
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
        // #213: non-members see the pre-game door; SiegeGateViewService handles their clicks.
        if (!gates.isLockdownMember(event.getPlayer(), structureId)) return;
        event.setCancelled(true);
        respond(event.getPlayer(), structureId, gates.clickOpenGate(event.getPlayer(), structureId));
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
