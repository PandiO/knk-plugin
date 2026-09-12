package net.knightsandkings.knk.paper.events;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when a player right-clicks a closed, active gate's door block, as resolved by
 * GateDoorHitService. Carries no consequence of its own (e.g. future pass-through) - listeners
 * decide what happens and can setCancelled() to have that propagate back to the underlying
 * PlayerInteractEvent.
 */
public class GateDoorInteractEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final CachedGateDoor gate;
    private final Player player;
    private final Block clickedBlock;
    private boolean cancelled;

    public GateDoorInteractEvent(CachedGateDoor gate, Player player, Block clickedBlock) {
        this.gate = gate;
        this.player = player;
        this.clickedBlock = clickedBlock;
    }

    public CachedGateDoor getGate() {
        return gate;
    }

    public Player getPlayer() {
        return player;
    }

    public Block getClickedBlock() {
        return clickedBlock;
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
