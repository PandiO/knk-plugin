package net.knightsandkings.knk.paper.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.ConvexPolyhedralRegion;
import com.sk89q.worldedit.regions.CylinderRegion;
import com.sk89q.worldedit.regions.EllipsoidRegion;
import com.sk89q.worldedit.regions.Polygonal2DRegion;
import com.sk89q.worldedit.regions.Region;

import net.knightsandkings.knk.core.regions.geometry.ConvexHull2D;
import net.knightsandkings.knk.core.regions.geometry.ConvexHull2D.Point;

/**
 * The WorldGuard polygon for a round WorldEdit selection (KNG-257, knk-workspace
 * {@code docs/specs/domain-subregions/DESIGN.md} §9.1). WorldGuard has no cylinder region, and every selection that is
 * not a cuboid or a poly2d used to become the box around it, so a round tower could not be drawn:
 * <ul>
 *   <li>{@code //sel cyl}: the convex hull of the block columns the cylinder holds, from its lowest to its highest block;</li>
 *   <li>{@code //sel ellipsoid}: the same over its widest (middle) slice, so the top and bottom are covered more than
 *       the ellipsoid ({@link Outline#note()} says so);</li>
 *   <li>{@code //sel convex}: the convex hull of its vertices seen from above.</li>
 * </ul>
 * A poly2d keeps its own points; a cuboid (and anything else) has no outline and stays a cuboid.
 */
public final class SelectionShapes {

    /**
     * A polygon for a selection.
     *
     * @param note what was made of the selection, for the player (null: nothing worth saying)
     */
    public record Outline(List<BlockVector2> points, int minY, int maxY, String note) {
    }

    private SelectionShapes() {
    }

    /** The polygon for {@code selection}, or empty for a cuboid (or a shape with no area). */
    public static Optional<Outline> outlineOf(Region selection) {
        if (selection instanceof Polygonal2DRegion poly) {
            return Optional.of(new Outline(poly.getPoints(), poly.getMinimumY(), poly.getMaximumY(), null));
        }
        int minY = selection.getMinimumPoint().y();
        int maxY = selection.getMaximumPoint().y();
        if (selection instanceof CylinderRegion cylinder) {
            return hullOfColumns(cylinder, minY, minY, maxY, "cylinder");
        }
        if (selection instanceof EllipsoidRegion ellipsoid) {
            int middle = (int) Math.floor(ellipsoid.getCenter().y());
            return hullOfColumns(ellipsoid, middle, minY, maxY, "ellipsoid")
                .map(outline -> new Outline(outline.points(), minY, maxY, outline.note()
                    + "; its top and bottom are covered as wide as its middle"));
        }
        if (selection instanceof ConvexPolyhedralRegion convex) {
            List<Point> vertices = new ArrayList<>();
            for (BlockVector3 vertex : convex.getVertices()) {
                vertices.add(new Point(vertex.x(), vertex.z()));
            }
            return polygon(ConvexHull2D.of(vertices), minY, maxY, "convex selection");
        }
        return Optional.empty();
    }

    /** The hull of the columns {@code shape} holds at height {@code sliceY}: per row its first and last block. */
    private static Optional<Outline> hullOfColumns(Region shape, int sliceY, int minY, int maxY, String what) {
        BlockVector3 min = shape.getMinimumPoint();
        BlockVector3 max = shape.getMaximumPoint();
        List<Point> edges = new ArrayList<>();
        for (int z = min.z(); z <= max.z(); z++) {
            Integer first = null;
            Integer last = null;
            for (int x = min.x(); x <= max.x(); x++) {
                if (shape.contains(BlockVector3.at(x, sliceY, z))) {
                    if (first == null) {
                        first = x;
                    }
                    last = x;
                }
            }
            if (first != null) {
                edges.add(new Point(first, z));
                edges.add(new Point(last, z));
            }
        }
        return polygon(ConvexHull2D.of(edges), minY, maxY, what);
    }

    private static Optional<Outline> polygon(List<Point> hull, int minY, int maxY, String what) {
        if (hull.size() < 3) {
            return Optional.empty();
        }
        List<BlockVector2> points = hull.stream().map(p -> BlockVector2.at(p.x(), p.z())).toList();
        return Optional.of(new Outline(points, minY, maxY, "The " + what + " is stored as a polygon with " + points.size()
            + " corners"));
    }
}
