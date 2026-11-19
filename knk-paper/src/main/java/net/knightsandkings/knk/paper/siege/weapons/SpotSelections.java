package net.knightsandkings.knk.paper.siege.weapons;

import net.knightsandkings.knk.core.siege.weapons.SpotBounds;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Where the spot task reads a spot's bounds (W4): the admin's WorldEdit selection or a WorldGuard region. A
 * non-cuboid shape is reduced to its bounding box, which is what a pasted weapon occupies.
 */
public interface SpotSelections {

    /** Either bounds or why there are none (no selection, unknown region ...). */
    record Read(SpotBounds bounds, String error, boolean boundingBoxOnly) {
        public static Read of(SpotBounds bounds, boolean boundingBoxOnly) {
            return new Read(bounds, null, boundingBoxOnly);
        }

        public static Read failed(String error) {
            return new Read(null, error, false);
        }

        public boolean ok() {
            return bounds != null;
        }
    }

    /** The player's current WorldEdit selection in their world. */
    Read worldEditSelection(Player player);

    /** A WorldGuard region of this world by id. */
    Read worldGuardRegion(World world, String regionId);
}
