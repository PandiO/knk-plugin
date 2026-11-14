package net.knightsandkings.knk.paper.enchantment;

import net.knightsandkings.knk.core.random.MarbleBagChanceRoll;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;

/** Drops a player's marble bags (PandiO/knk-workspace#476) when they leave, so the store doesn't grow with every visitor. */
public final class FairChanceCleanup implements Listener {
    private final MarbleBagChanceRoll chanceRoll;

    public FairChanceCleanup(MarbleBagChanceRoll chanceRoll) {
        this.chanceRoll = Objects.requireNonNull(chanceRoll, "chanceRoll must not be null");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        chanceRoll.forget(event.getPlayer().getUniqueId());
    }
}
