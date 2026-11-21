package net.knightsandkings.knk.paper.production.harvest;

import java.util.Objects;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import com.sk89q.worldguard.bukkit.event.block.BreakBlockEvent;
import com.sk89q.worldguard.bukkit.event.entity.DamageEntityEvent;

/**
 * Lets a qualified harvest through WorldGuard (PandiO/knk-workspace#134, KNG-48 / #105): a {@code RESOURCE_PRODUCTION}
 * region denies {@code block-break} to non-members, and WorldGuard has no per-block {@code allow-blocks}. For a break the
 * harvest gate allowed (decided at LOW of the Bukkit event, before WorldGuard's region check at NORMAL, which skips an
 * allowed event), the WorldGuard event is marked allowed; likewise damage to an entity a rule lets the player harvest.
 * Every other break, and refusals by other plugins, are left alone (as {@code HubWorldGuardListener} does for uses).
 */
final class HarvestWorldGuardListener implements Listener {

    private final HarvestModule module;

    HarvestWorldGuardListener(HarvestModule module) {
        this.module = Objects.requireNonNull(module, "module");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BreakBlockEvent event) {
        Player player = event.getCause().getFirstPlayer();
        if (player == null || event.getBlocks().isEmpty()) {
            return;
        }
        for (Block block : event.getBlocks()) {
            if (!module.allowed(player, block)) {
                return;
            }
        }
        event.setResult(Event.Result.ALLOW);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(DamageEntityEvent event) {
        Player player = event.getCause().getFirstPlayer();
        if (player != null && module.allowed(player, event.getEntity())) {
            event.setResult(Event.Result.ALLOW);
        }
    }
}
