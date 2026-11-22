package net.knightsandkings.knk.core.regions.query;

import java.util.Optional;

/** PandiO/knk-workspace#988: finds a WorldGuard region's shape. Main thread (WorldGuard). */
public interface RegionShapeLookup {

    /**
     * @param world the world to look in; null, blank or not loaded = every loaded world, the first that has the region
     * @return the shape, or empty when no such region (the global region has no shape)
     */
    Optional<RegionShape> find(String regionId, String world);
}
