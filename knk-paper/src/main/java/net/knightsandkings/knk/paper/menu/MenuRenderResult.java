package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.menu.RuntimeMenuItem;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * The computed "desired state" of one render pass: which {@link ItemStack}
 * goes in which absolute slot, and which {@link RuntimeMenuItem} (if any)
 * backs that slot for click routing. Deliberately separate from actually
 * touching a Bukkit {@code Inventory} - computing this is the "expensive
 * work" ARCHITECTURE_DESIGN.md §6.3 says belongs off the main thread;
 * applying it is the cheap part that must happen back on the main thread.
 */
public record MenuRenderResult(
        Map<Integer, ItemStack> itemStacksBySlot,
        Map<Integer, RuntimeMenuItem> itemsBySlot
) {
}
