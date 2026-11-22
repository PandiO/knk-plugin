package net.knightsandkings.knk.core.regions.query;

import java.util.List;
import java.util.Objects;

/**
 * PandiO/knk-workspace#988: a WorldGuard region's shape as the game server answers the API's region geometry query:
 * the world it is in, its kind ({@code cuboid}, {@code polygon}, ...), its min and max corner (a polygon's bounding box)
 * and, for a polygon, its points. Bukkit-free; the paper side reads it from WorldGuard.
 *
 * @param points a polygon's x/z points in order (each {@code int[]{x, z}}); empty for a cuboid
 */
public record RegionShape(String world, String kind, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                          List<int[]> points) {

    public RegionShape {
        Objects.requireNonNull(world, "world");
        kind = kind == null || kind.isBlank() ? "cuboid" : kind.trim().toLowerCase(java.util.Locale.ROOT);
        points = points == null ? List.of() : List.copyOf(points);
    }

    /** The bounding box as the siege weapon spot classes read it (knk-core {@code SpotBounds}). */
    public net.knightsandkings.knk.core.siege.weapons.SpotBounds bounds() {
        return new net.knightsandkings.knk.core.siege.weapons.SpotBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
