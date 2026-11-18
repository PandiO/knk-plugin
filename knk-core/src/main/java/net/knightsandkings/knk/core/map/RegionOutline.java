package net.knightsandkings.knk.core.map;

import java.util.List;
import java.util.Objects;

/**
 * A region's 2D outline as the overview map stores it (OVERVIEW_MAP_DESIGN § 6.1.1, O28): the points in order (a cuboid
 * region's four corners, a polygon region's points; block coordinates, inclusive) and the Y range.
 *
 * @param regionId  the WorldGuard region id
 * @param worldName the world the region was found in (never null)
 */
public record RegionOutline(String regionId, String worldName, List<OutlinePoint> points, int minY, int maxY) {

    public RegionOutline {
        Objects.requireNonNull(regionId, "regionId");
        Objects.requireNonNull(worldName, "worldName");
        points = List.copyOf(points);
    }

    public RegionRef ref() {
        return new RegionRef(worldName, regionId);
    }
}
