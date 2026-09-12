package net.knightsandkings.knk.paper.events;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when a player, entity, projectile, or explosion hits a closed, active, non-destroyed
 * gate's door block, as resolved by GateDoorHitService. Deliberately carries no damage amount -
 * detection and the amount to apply are kept separate; see GateDamageConsequenceListener, which
 * owns the per-Cause damage amount and calls HealthSystem.applyDamage.
 */
public class GateDoorDamageEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    public enum Cause {
        LEFT_CLICK,
        PROJECTILE,
        EXPLOSION,
        BLOCK_BREAK
    }

    private final CachedGateDoor gate;
    private final Cause cause;
    private final Entity causingEntity;
    private final Block hitBlock;
    private boolean cancelled;

    public GateDoorDamageEvent(CachedGateDoor gate, Cause cause, Entity causingEntity, Block hitBlock) {
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
     * The entity that caused the hit, or null when none is available (e.g. a block-caused
     * explosion such as a bed detonation).
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
