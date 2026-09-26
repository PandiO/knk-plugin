package net.knightsandkings.knk.paper.menu;

import org.bukkit.inventory.ItemStack;

/**
 * Menu follow-up 2026-09-26: a row (E3) that knows exactly what it should look like - e.g. the item
 * catalogue shows each blueprint as the ItemStack the player would actually receive. When a row
 * template renders such a row, the renderer starts from {@link #menuItemStack()} instead of the
 * template's material/name, and appends the template's resolved lore lines below the item's own
 * lore. Returning null falls back to the ordinary template rendering. Called on the main thread;
 * the renderer clones the result.
 */
public interface MenuItemStackRow {
    ItemStack menuItemStack();
}
