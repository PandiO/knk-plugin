package net.knightsandkings.knk.paper.events;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when a closed, active, non-destroyed gate's door block is hit by something that should
 * set it alight (a flaming projectile, a fire charge), as resolved by GateDoorHitService.
 * Deliberately separate from GateDoorDamageEvent - igniting a block and damaging a gate are
 * distinct consequences of a hit, and a listener may want to react to one without the other (see
 * GateFireSystem, which owns the burn-duration/damage-over-time bookkeeping).
 */
public class GateDoorIgniteEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    public enum Cause {
        FLAMING_PROJECTILE,
        FIRE_CHARGE,
        FLINT_AND_STEEL
    }

    private final CachedGateDoor gate;
    private final Cause cause;
    private final Entity causingEntity;
    private final Block hitBlock;
    private boolean cancelled;

    public GateDoorIgniteEvent(CachedGateDoor gate, Cause cause, Entity causingEntity, Block hitBlock) {
        this.gate = gate;
        this.cause = cause;
        this.causingEntity = causingEntity;
        this.hitBlock = hitBlock;
    }

    public CachedGateDoor getGate() {
        return gate;
    }

    public Cause getCause() {
        return cause;
    }

    /**
     * The entity that caused the ignition, or null when none is available.
     */
    public Entity getCausingEntity() {
        return causingEntity;
    }

    public Block getHitBlock() {
        return hitBlock;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
