package net.knightsandkings.knk.paper.menu.opener;

import org.bukkit.inventory.ItemStack;

import net.knightsandkings.knk.core.menu.opener.MenuOpenerPlacement;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings;

/**
 * The Bukkit side of the menu item (PandiO/knk-workspace#160): making it and recognising it. A seam so the
 * service and listener are tested without Bukkit's item factory.
 */
public interface MenuOpenerItems {

    /** A new menu item for the settings, or null when the material is no usable item. */
    ItemStack create(MenuOpenerSettings settings);

    /** Whether {@code item} is a menu item, of any settings (its tag, not its look). */
    boolean isOpener(ItemStack item);

    /** What a slot holds under the current settings. */
    MenuOpenerPlacement.Slot classify(ItemStack item, MenuOpenerSettings settings);
}
