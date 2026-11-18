package net.knightsandkings.knk.core.landmarks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import net.knightsandkings.knk.core.navigation.model.Landmark;
import net.knightsandkings.knk.core.roads.route.RegionShape;
import net.knightsandkings.knk.core.roads.walk.WalkCellWeights;

/**
 * Landmark preference weights on walk cells (off-road navigation phase 5.2, PandiO/knk-workspace#709, DESIGN § 5.4.3,
 * § 5.4.4 and § 6.2.3; decision O02: preference indoors without an indoor road graph). A landmark with an extent (a
 * WorldGuard region or a cylinder around its pin) and a {@code preference} other than 1.0 is a weighted
 * {@link Area}: the cost of every walk cell whose feet block is inside it is multiplied by the preference. Overlapping
 * areas multiply; the product is capped at {@code preference-max} (default {@value #DEFAULT_MAX}) and, the other way,
 * at its inverse, so one bad overlap can neither block a hall nor make it nearly free.
 *
 * <p>Two steps, both Bukkit-free:
 * <ol>
 *   <li>{@link #areas} on the main thread, where the game server resolves a Region extent to a {@link RegionShape}
 *       (WorldGuard is not read off the main thread);</li>
 *   <li>{@link #within} per walk request on the walk thread: only the areas whose box meets the request's
 *       {@link net.knightsandkings.knk.core.roads.walk.WalkRequest#reachBox() reach box} are kept, so the lookup per
 *       cell is a few box tests (usually none: no landmark near the leg gives {@link WalkCellWeights#NONE}).</li>
 * </ol>
 *
 * <p>The heuristic's scale ({@link WalkCellWeights#minMultiplier()}) is the product of every preference below 1.0
 * among the kept areas (as if they all overlapped), held at the lower cap, and never above 1.0: a cell outside every
 * area costs ×1.0 and the search area is never known to lie wholly inside the weighted areas, so the heuristic is not
 * raised even when every weight is above 1.0. That keeps it admissible (DESIGN § 5.4.4).
 *
 * <p>Immutable; safe on any thread.
 */
public final class LandmarkWeights {

    /** {@code navigation.preference-max}'s default (DESIGN § 5.4.3). */
    public static final double DEFAULT_MAX = 4.0;

    /** Nothing weighted. */
    public static final LandmarkWeights NONE = new LandmarkWeights(List.of(), DEFAULT_MAX);

    /**
     * A weighted area.
     *
     * @param landmarkId the landmark it comes from
     * @param shape      where it is
     * @param preference the multiplier inside it (above 0, finite, not 1.0)
     */
    public record Area(int landmarkId, Shape shape, double preference) {
        public Area {
            Objects.requireNonNull(shape, "shape");
            if (!(preference > 0) || !Double.isFinite(preference)) {
                throw new IllegalArgumentException("preference must be positive and finite: " + preference);
            }
        }
    }

    /** A Bukkit-free extent: a WorldGuard region's shape or a landmark's cylinder. */
    public sealed interface Shape permits RegionArea, CylinderArea {

        /** Whether the cell whose feet block is {@code (x, feetY, z)} is inside. */
        boolean contains(int x, int feetY, int z);

        /** The block box, {@code {minX, minY, minZ, maxX, maxY, maxZ}} (feet y), that holds every cell inside. */
        int[] box();
    }

    /**
     * A region extent: containment of the cell's feet block against the region's points and y band, the rule of the
     * walk search's denied regions ({@code DeniedRegionAccess}).
     */
    public record RegionArea(RegionShape region) implements Shape {
        public RegionArea {
            Objects.requireNonNull(region, "region");
        }

        @Override
        public boolean contains(int x, int feetY, int z) {
            return region.contains(x, feetY, z);
        }

        @Override
        public int[] box() {
            double x0 = Double.POSITIVE_INFINITY, x1 = Double.NEGATIVE_INFINITY;
            double z0 = Double.POSITIVE_INFINITY, z1 = Double.NEGATIVE_INFINITY;
            for (double[] p : region.points()) {
                x0 = Math.min(x0, p[0]);
                x1 = Math.max(x1, p[0]);
                z0 = Math.min(z0, p[1]);
                z1 = Math.max(z1, p[1]);
            }
            return new int[] {(int) Math.floor(x0), region.minY(), (int) Math.floor(z0),
                (int) Math.ceil(x1), region.maxY(), (int) Math.ceil(z1)};
        }
    }

    /** A Radius extent: the cylinder of {@link LandmarkExtent.Cylinder}, tested at the centre of the feet block. */
    public record CylinderArea(LandmarkExtent.Cylinder cylinder) implements Shape {
        public CylinderArea {
            Objects.requireNonNull(cylinder, "cylinder");
        }

        @Override
        public boolean contains(int x, int feetY, int z) {
            return cylinder.contains(x + 0.5, feetY, z + 0.5);
        }

        @Override
        public int[] box() {
            double r = cylinder.radius();
            return new int[] {(int) Math.floor(cylinder.x() - r), (int) Math.floor(cylinder.y() - r),
                (int) Math.floor(cylinder.z() - r), (int) Math.ceil(cylinder.x() + r),
                (int) Math.ceil(cylinder.y() + r), (int) Math.ceil(cylinder.z() + r)};
        }
    }

    private final List<Area> areas;
    private final double max;

    private LandmarkWeights(List<Area> areas, double max) {
        this.areas = List.copyOf(areas);
        this.max = max;
    }

    /**
     * The weights of {@code areas} with the cap {@code preferenceMax}; a cap that is not finite or below 1.0 is
     * {@link #DEFAULT_MAX}.
     */
    public static LandmarkWeights of(List<Area> areas, double preferenceMax) {
        Objects.requireNonNull(areas, "areas");
        return new LandmarkWeights(areas, validMax(preferenceMax));
    }

    /** A usable {@code preference-max}: finite and at least 1.0, otherwise {@link #DEFAULT_MAX}. */
    public static double validMax(double preferenceMax) {
        return Double.isFinite(preferenceMax) && preferenceMax >= 1.0 ? preferenceMax : DEFAULT_MAX;
    }

    /**
     * Main thread: the weighted areas of {@code landmarks} in {@code world} (case-insensitive): those with an extent and a
     * preference other than 1.0 ({@link Landmark#weighted()}). A Region extent is looked up with {@code regions}
     * (the game server's WorldGuard regions); a region it does not know is left out. A landmark that is not offered as
     * a destination ({@code navigationEnabled} false) still weights its area: the weight is about the place, not the
     * destination list.
     */
    public static List<Area> areas(List<Landmark> landmarks, String world,
                                   Function<String, Optional<RegionShape>> regions) {
        Objects.requireNonNull(regions, "regions");
        if (landmarks == null || landmarks.isEmpty() || world == null) {
            return List.of();
        }
        String w = world.toLowerCase(Locale.ROOT);
        List<Area> out = new ArrayList<>();
        for (Landmark landmark : landmarks) {
            if (landmark == null || !landmark.weighted() || !landmark.world().toLowerCase(Locale.ROOT).equals(w)) {
                continue;
            }
            LandmarkExtent extent = LandmarkExtent.of(landmark);
            Shape shape = switch (extent) {
                case LandmarkExtent.Cylinder cylinder -> new CylinderArea(cylinder);
                case LandmarkExtent.Region region -> regions.apply(region.regionId()).map(RegionArea::new).orElse(null);
                case LandmarkExtent.Point point -> null;
            };
            if (shape != null) {
                out.add(new Area(landmark.id(), shape, landmark.preference()));
            }
        }
        return out;
    }

    public List<Area> areas() {
        return areas;
    }

    public double preferenceMax() {
        return max;
    }

    public boolean isEmpty() {
        return areas.isEmpty();
    }

    /**
     * Walk thread: the cell weights of the areas whose box meets {@code box} ({@code {minX, minY, minZ, maxX, maxY,
     * maxZ}}, a walk request's reach box); {@link WalkCellWeights#NONE} when none does.
     */
    public WalkCellWeights within(int[] box) {
        Objects.requireNonNull(box, "box");
        List<Area> kept = new ArrayList<>();
        List<int[]> boxes = new ArrayList<>();
        for (Area area : areas) {
            int[] b = area.shape().box();
            if (b[0] <= box[3] && b[3] >= box[0] && b[1] <= box[4] && b[4] >= box[1] && b[2] <= box[5] && b[5] >= box[2]) {
                kept.add(area);
                boxes.add(b);
            }
        }
        if (kept.isEmpty()) {
            return WalkCellWeights.NONE;
        }
        return new Cells(kept.toArray(Area[]::new), boxes.toArray(int[][]::new), max);
    }

    /** The cell weights of one request: a box test, then the shape test, per kept area. */
    static final class Cells implements WalkCellWeights {
        private final Area[] areas;
        private final int[][] boxes;
        private final double max;
        private final double min;
        private final double lowest;

        Cells(Area[] areas, int[][] boxes, double max) {
            this.areas = areas;
            this.boxes = boxes;
            this.max = max;
            this.min = 1.0 / max;
            double product = 1.0;
            for (Area area : areas) {
                if (area.preference() < 1.0) {
                    product *= area.preference();
                }
            }
            this.lowest = Math.min(1.0, Math.max(min, product));
        }

        @Override
        public double multiplier(int x, int feetY, int z) {
            double m = 1.0;
            boolean inside = false;
            for (int i = 0; i < areas.length; i++) {
                int[] b = boxes[i];
                if (x < b[0] || x > b[3] || feetY < b[1] || feetY > b[4] || z < b[2] || z > b[5]) {
                    continue;
                }
                if (areas[i].shape().contains(x, feetY, z)) {
                    m *= areas[i].preference();
                    inside = true;
                }
            }
            return inside ? Math.max(min, Math.min(max, m)) : 1.0;
        }

        @Override
        public double minMultiplier() {
            return lowest;
        }

        @Override
        public String toString() {
            return "LandmarkWeights.Cells[" + areas.length + " areas, max " + max + "]";
        }
    }
}
