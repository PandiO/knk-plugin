package net.knightsandkings.knk.core.production.survey;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A production area as the API sends it (knk-web-api {@code ProductionAreaDto} / {@code ProductionAreaOverlapDto},
 * PandiO/knk-workspace#138): its shape name and the fields of that shape, plus its bounding box. {@link #resolve} turns
 * it into an {@link AreaShape}; a WorldGuard region is looked up through {@link RegionShapes}.
 *
 * @param shape {@code Region}, {@code Cuboid}, {@code Polygon} or {@code Radius}
 */
public record AreaSpec(int areaId, int structureId, String structureName, String name, String shape, boolean exclusive,
                       boolean enabled, String worldName, String wgRegionId, List<int[]> points, Integer centerX,
                       Integer centerY, Integer centerZ, Integer radius, Integer minX, Integer minY, Integer minZ,
                       Integer maxX, Integer maxY, Integer maxZ) {

    public AreaSpec {
        points = points == null ? List.of() : List.copyOf(points);
    }

    /** Port: a WorldGuard region's shape (the Paper adapter reads it on the main thread). */
    @FunctionalInterface
    public interface RegionShapes {
        Optional<AreaShape> shapeOf(String world, String regionId);

        RegionShapes NONE = (world, regionId) -> Optional.empty();
    }

    /** A readable label: {@code #12 "North field" of structure #4 Farm}. */
    public String label() {
        return "#" + areaId + (name == null || name.isBlank() ? "" : " \"" + name + "\"") + " of structure #" + structureId
                + (structureName == null || structureName.isBlank() ? "" : " " + structureName);
    }

    /**
     * The area's volume, clamped to the world's height ({@code worldMinY} inclusive, {@code worldMaxY} exclusive).
     *
     * @throws IllegalArgumentException with the reason when the shape can't be built (unknown shape, missing fields,
     *                                  a region that doesn't exist)
     */
    public AreaShape resolve(RegionShapes regions, int worldMinY, int worldMaxY) {
        int top = worldMaxY - 1;
        String kind = shape == null ? "" : shape.trim().toLowerCase(Locale.ROOT);
        AreaShape resolved = switch (kind) {
            case "region" -> {
                if (wgRegionId == null || wgRegionId.isBlank()) {
                    throw new IllegalArgumentException("region area without wgRegionId");
                }
                yield (regions == null ? RegionShapes.NONE : regions).shapeOf(worldName, wgRegionId.trim())
                        .orElseThrow(() -> new IllegalArgumentException("WorldGuard region " + wgRegionId + " not found"
                                + (worldName == null ? "" : " in " + worldName)));
            }
            case "cuboid" -> {
                if (minX == null || minZ == null || maxX == null || maxZ == null) {
                    throw new IllegalArgumentException("cuboid area without corners");
                }
                yield new AreaShape.Cuboid(minX, minY == null ? worldMinY : minY, minZ, maxX, maxY == null ? top : maxY, maxZ);
            }
            case "polygon" -> {
                if (points.size() < 3) {
                    throw new IllegalArgumentException("polygon area with fewer than 3 points");
                }
                yield AreaShape.Polygon.of(points, minY == null ? worldMinY : minY, maxY == null ? top : maxY);
            }
            case "radius" -> {
                if (centerX == null || centerZ == null || radius == null) {
                    throw new IllegalArgumentException("radius area without centre or radius");
                }
                yield new AreaShape.Radius(centerX, centerZ, radius, minY == null ? worldMinY : minY,
                        maxY == null ? top : maxY);
            }
            default -> throw new IllegalArgumentException("unknown area shape " + shape);
        };
        return resolved.clampY(worldMinY, top);
    }
}
