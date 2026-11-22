package net.knightsandkings.knk.core.landmarks;

import java.util.List;
import java.util.Objects;

import net.knightsandkings.knk.core.navigation.model.Landmark;

/**
 * Where a landmark counts as reached (off-road DESIGN § 6.2.1; landmark-domain DESIGN § 7.1 item 4, LD08, LD22,
 * PandiO/knk-workspace#985), Bukkit-free: its pin only ({@link Point}: within {@code arrive-distance}), or its
 * {@link Area}, the union of its regions (its own main region and sub-regions, or the parent's for a
 * {@code LinkedDomain} landmark; the region test is the game server's). The cylinder around the pin is gone: a round
 * area is a cylinder-shaped region drawn with {@code //sel cyl}.
 */
public sealed interface LandmarkExtent permits LandmarkExtent.Point, LandmarkExtent.Area {

    /** The extent of {@code landmark}: an {@link Area} when it has regions, else a {@link Point}. */
    static LandmarkExtent of(Landmark landmark) {
        Objects.requireNonNull(landmark, "landmark");
        return landmark.hasArea() ? new Area(landmark.regionIds()) : new Point();
    }

    /** No area: the pin, arrived at within {@code arrive-distance}. */
    record Point() implements LandmarkExtent {
    }

    /**
     * The union of WorldGuard regions of the landmark's world, main region first; inside any of them is inside the area.
     */
    record Area(List<String> regionIds) implements LandmarkExtent {
        public Area {
            regionIds = Landmark.cleanRegionIds(regionIds);
            if (regionIds.isEmpty()) {
                throw new IllegalArgumentException("an area needs a region");
            }
        }

        /** The main region (the routing goal). */
        public String mainRegionId() {
            return regionIds.get(0);
        }

        /** The other regions (the sub-regions). */
        public List<String> otherRegionIds() {
            return regionIds.subList(1, regionIds.size());
        }
    }
}
