package net.knightsandkings.knk.paper.regions;

import java.util.Objects;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import com.sk89q.worldguard.bukkit.event.block.UseBlockEvent;
import com.sk89q.worldguard.bukkit.event.entity.UseEntityEvent;

/**
 * Applies {@link ContainerAccessBypass} (PandiO/knk-workspace#157): for a player who bypasses, WorldGuard's use event
 * on storage is marked allowed before WorldGuard's region check (which runs at NORMAL and skips an allowed event) -
 * the same pattern as {@code HubWorldGuardListener} and {@code LootboxWorldGuardListener}. A refusal by another
 * plugin (an already-cancelled event) is left alone, as are siege and hub rules, which refuse on Bukkit's events.
 */
public final class ContainerAccessWorldGuardListener implements Listener {

    private final ContainerAccessBypass bypass;

    public ContainerAccessWorldGuardListener(ContainerAccessBypass bypass) {
        this.bypass = Objects.requireNonNull(bypass, "bypass");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onUseBlock(UseBlockEvent event) {
        Player player = event.getCause().getFirstPlayer();
        if (player != null && bypass.allowsBlocks(player, event.getBlocks())) {
            event.setResult(Event.Result.ALLOW);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onUseEntity(UseEntityEvent event) {
        Player player = event.getCause().getFirstPlayer();
        if (player != null && bypass.allowsEntity(player, event.getEntity())) {
            event.setResult(Event.Result.ALLOW);
        }
    }
}
