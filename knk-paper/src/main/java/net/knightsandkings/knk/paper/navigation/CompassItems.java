package net.knightsandkings.knk.paper.navigation;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.knightsandkings.knk.core.navigation.compass.CompassItemRule;

/**
 * The Bukkit side of handing out the navigation compass (KNG-234): what the item looks like, whether a
 * player already carries one, and putting it in their inventory. A seam so {@link NavigationCompassService}'s
 * granting rules are tested without Bukkit's {@code Material}.
 */
public interface CompassItems {

    /** A new compass item for {@code rule}, or null when the rule names no usable material. */
    ItemStack create(CompassItemRule rule);

    /** The name the player is told, e.g. {@code "Navigation Compass"}. */
    String nameOf(CompassItemRule rule);

    /** Whether the player already carries an item the rule accepts as the compass. */
    boolean holds(Player player, CompassItemRule rule);

    /** Puts the item in the player's inventory; false when it did not fit (nothing is dropped). */
    boolean give(Player player, ItemStack item);
}
