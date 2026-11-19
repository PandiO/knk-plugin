package net.knightsandkings.knk.paper.territory;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

import net.knightsandkings.knk.core.territory.TerritoryCache;
import net.knightsandkings.knk.core.territory.TerritoryVerdict;

/**
 * A player crossed a territory border (territory DESIGN § 1 "entry and exit checks", PandiO/knk-workspace#689): the area
 * they stand in changed from {@link #from()} to {@link #to()} (an owner, a contested set, or wilderness). Fired on the main
 * thread after the move; informational (later links read it for entry and exit rules).
 */
public final class PlayerTerritoryChangeEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final TerritoryVerdict from;
    private final TerritoryVerdict to;
    private final TerritoryCache cache;

    public PlayerTerritoryChangeEvent(Player player, TerritoryVerdict from, TerritoryVerdict to, TerritoryCache cache) {
        super(player);
        this.from = from;
        this.to = to;
        this.cache = cache;
    }

    public TerritoryVerdict from() {
        return from;
    }

    public TerritoryVerdict to() {
        return to;
    }

    /** The cache the verdicts come from (names, version). */
    public TerritoryCache cache() {
        return cache;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
