package net.knightsandkings.knk.paper.navigation;

import java.util.Optional;

import net.knightsandkings.knk.core.roads.route.RegionShape;

/**
 * Port: a WorldGuard region as the Bukkit-free {@link RegionShape} (DESIGN §6.3), for region-mode
 * destinations. {@link WorldGuardRegionShapes} is the server implementation; tests fake it.
 */
@FunctionalInterface
public interface RegionShapes {

    /** The region {@code regionId} of world {@code world}; empty when the world or region is unknown or global. */
    Optional<RegionShape> shape(String world, String regionId);
}
