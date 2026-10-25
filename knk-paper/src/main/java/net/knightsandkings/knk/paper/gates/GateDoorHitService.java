package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.paper.events.GateDoorDamageEvent;
import net.knightsandkings.knk.paper.events.GateDoorIgniteEvent;
import net.knightsandkings.knk.paper.events.GateDoorInteractEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Resolves whether a hit world block belongs to a gate's animated door, via GateManager's O(1)
 * spatial index, and fires the corresponding cancellable custom event. Kept as a plain service
 * (not a Bukkit Listener) so any future caller - a new NPC/melee framework, an admin command,
 * a different Bukkit event - can invoke detection directly without needing a synthetic Bukkit
 * event to translate through. GateEventListener is the current Bukkit-event adapter on top of it.
 */
public class GateDoorHitService {
    private final GateManager gateManager;

    public GateDoorHitService(GateManager gateManager) {
        this.gateManager = gateManager;
    }

    /**
     * Resolve the gate whose door currently occupies the given world block, or null if the
     * block isn't part of any gate's door right now.
     */
    public CachedGateDoor resolveDoorGate(String worldName, Block block) {
        if (block == null) {
            return null;
        }
        Integer gateId = gateManager.getSpatialIndex().lookup(worldName, block.getX(), block.getY(), block.getZ());
        return gateId == null ? null : gateManager.getGate(gateId);
    }

    /**
     * Fire a GateDoorInteractEvent for a right-click on the gate's door, iff the gate is closed
     * and active. Returns the fired event so the caller can check isCancelled() and propagate
     * that back to the originating Bukkit event, or null if the gate doesn't qualify.
     */
    public GateDoorInteractEvent handleInteract(CachedGateDoor gate, Player player, Block clickedBlock) {
        if (!qualifiesForInteraction(gate)) {
            return null;
        }

        GateDoorInteractEvent event = new GateDoorInteractEvent(gate, player, clickedBlock);
        Bukkit.getPluginManager().callEvent(event);
        return event;
    }

    /**
     * Fire a GateDoorDamageEvent for a hit on the gate's door, iff the gate is closed, active,
     * not already destroyed, and the causing player (if any) is in Survival - a Creative/Spectator
     * player (or one who shot the arrow/lit the fire) risks nothing, so their hits shouldn't count
     * as real siege damage. Deliberately does not check isInvincible() - HealthSystem's damage
     * handler already no-ops on that, so checking it twice would just duplicate the rule
     * (ignition is the exception, see {@link #handleIgnite}).
     * Returns the fired event, or null if the gate doesn't qualify.
     */
    public GateDoorDamageEvent handleDamage(CachedGateDoor gate, Entity causingEntity, Block hitBlock, GateDoorDamageEvent.Cause cause) {
        return handleDamage(gate, causingEntity, hitBlock, cause, null);
    }

    /**
     * As {@link #handleDamage(CachedGateDoor, Entity, Block, GateDoorDamageEvent.Cause)}, for a source that
     * deals a damage figure of its own (an area enchantment); null keeps the amount for the cause.
     */
    public GateDoorDamageEvent handleDamage(CachedGateDoor gate, Entity causingEntity, Block hitBlock,
                                            GateDoorDamageEvent.Cause cause, Double damage) {
        if (gate == null || gate.getCurrentState() != AnimationState.CLOSED || !gate.isEffectivelyActive() || gate.isEffectivelyDestroyed()
            || !isSurvivalOrNonPlayer(causingEntity)) {
            return null;
        }

        GateDoorDamageEvent event = new GateDoorDamageEvent(gate, cause, causingEntity, hitBlock, damage);
        Bukkit.getPluginManager().callEvent(event);
        return event;
    }

    /**
     * Fire a GateDoorIgniteEvent for a hit that should set the gate's door block alight (a
     * flaming projectile or fire charge), iff the gate is closed, active, not already destroyed,
     * not invincible, and the igniting player (if any) is in Survival - the handleDamage
     * qualification plus invincibility, since a door block only occupies a stable world position
     * (the thing that actually "catches fire") while CLOSED. Unlike a direct hit, ignition is
     * refused outright for an effectively-invincible gate: a burn on it would deal nothing (the
     * continuous damage no-ops) yet still show fire and smoke for its duration, which reads as a
     * gate that can be hurt. Returns the fired event, or null if the gate doesn't qualify.
     */
    public GateDoorIgniteEvent handleIgnite(CachedGateDoor gate, Entity causingEntity, Block hitBlock, GateDoorIgniteEvent.Cause cause) {
        return handleIgnite(gate, causingEntity, hitBlock, cause, null);
    }

    /** As above; {@code hitFace} is the side of {@code hitBlock} the ignition came from (null when unknown). */
    public GateDoorIgniteEvent handleIgnite(CachedGateDoor gate, Entity causingEntity, Block hitBlock,
                                            GateDoorIgniteEvent.Cause cause, BlockFace hitFace) {
        if (gate == null || gate.getCurrentState() != AnimationState.CLOSED || !gate.isEffectivelyActive() || gate.isEffectivelyDestroyed()
            || gate.isEffectivelyInvincible() || !isSurvivalOrNonPlayer(causingEntity)) {
            return null;
        }

        GateDoorIgniteEvent event = new GateDoorIgniteEvent(gate, cause, causingEntity, hitBlock, hitFace);
        Bukkit.getPluginManager().callEvent(event);
        return event;
    }

    private static boolean qualifiesForInteraction(CachedGateDoor gate) {
        return gate != null && gate.getCurrentState() == AnimationState.CLOSED && gate.isEffectivelyActive();
    }

    /**
     * A non-player causing entity (a mob, an explosion with no attributable player, etc.) always
     * qualifies - gamemode is a player-only concept. A player only qualifies while in Survival:
     * Creative/Spectator/Adventure players can't be legitimately sieging a gate, whether they're
     * hitting it directly or are the shooter of an arrow / the one who lit a fire on it.
     */
    private static boolean isSurvivalOrNonPlayer(Entity causingEntity) {
        return !(causingEntity instanceof Player player) || player.getGameMode() == GameMode.SURVIVAL;
    }
}
