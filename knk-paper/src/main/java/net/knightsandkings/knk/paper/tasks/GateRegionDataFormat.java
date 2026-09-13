package net.knightsandkings.knk.paper.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.ConvexPolyhedralRegion;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Polygonal2DRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.regions.RegionSelector;
import com.sk89q.worldedit.regions.selector.ConvexPolyhedralRegionSelector;
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector;
import com.sk89q.worldedit.regions.selector.Polygonal2DRegionSelector;
import com.sk89q.worldedit.regions.selector.limit.PermissiveSelectorLimits;
import com.sk89q.worldedit.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the region vertex JSON shape stored in {@code GateDoor.ClosedRegionData}/
 * {@code OpenedRegionData} - documented in {@code WORLDGUARD_REGION_FEASIBILITY.md} §9.1. Shared
 * by {@link GateDoorRegionCaptureHandler} (items 6.3/6.4 - writes it on save, reads it back on
 * redefine) and {@link GateBlockScanTaskHandler} (item 6.5 - reads it for REGION-mode scanning),
 * so the shape has exactly one read implementation and one write implementation, not four
 * independently-drifting copies. Also used by {@code GateLoaderAdapter} (a different package,
 * {@code knk-paper.gates}) via {@link #extractFootprintVerticesXYZ}/{@link
 * #isConvexPolyhedron}, so this class is public.
 *
 * <p>Three capture shapes are supported, one per WorldEdit selection type: {@code POLYGON2D}
 * ({@link Polygonal2DRegion} - an X/Z outline extruded through a Y range), {@code CUBOID} ({@link
 * CuboidRegion} - two corner points, always world-axis-aligned), and {@code CONVEX_POLYHEDRON}
 * ({@link ConvexPolyhedralRegion} - an arbitrary set of 3D vertices forming a convex hull, drawn
 * with {@code //sel convex}). The first two can only precisely represent a genuinely
 * horizontal-ish or axis-aligned footprint (see the design note on {@code GateLoaderAdapter
 * .precomputeFootprintPolygons}); {@code CONVEX_POLYHEDRON} is the general-purpose answer for any
 * other orientation (vertical, diagonal, or both) - each vertex carries its own real (x, y, z),
 * so nothing collapses.
 */
public final class GateRegionDataFormat {
    private GateRegionDataFormat() {
    }

    /**
     * Serializes a WorldEdit selection into the §9.1 shape: a polygon (points + Y range) for a
     * {@link Polygonal2DRegion}, a convex hull (raw 3D vertices) for a {@link
     * ConvexPolyhedralRegion}, or a cuboid (two corner points) for any other region type.
     */
    static String serialize(Region selection, String worldName) {
        JsonObject json = new JsonObject();
        json.addProperty("worldName", worldName);

        if (selection instanceof Polygonal2DRegion poly) {
            json.addProperty("type", "POLYGON2D");
            JsonArray points = new JsonArray();
            for (BlockVector2 point : poly.getPoints()) {
                JsonObject p = new JsonObject();
                p.addProperty("x", point.x());
                p.addProperty("z", point.z());
                points.add(p);
            }
            json.add("points", points);
            json.addProperty("minY", poly.getMinimumY());
            json.addProperty("maxY", poly.getMaximumY());
        } else if (selection instanceof ConvexPolyhedralRegion convex) {
            json.addProperty("type", "CONVEX_POLYHEDRON");
            JsonArray points = new JsonArray();
            for (BlockVector3 vertex : convex.getVertices()) {
                points.add(vectorToJson(vertex));
            }
            json.add("points", points);
        } else {
            json.addProperty("type", "CUBOID");
            json.add("pos1", vectorToJson(selection.getMinimumPoint()));
            json.add("pos2", vectorToJson(selection.getMaximumPoint()));
        }

        return json.toString();
    }

    /** For pure region math (containment/iteration) - item 6.5's headless scanning. */
    static Region parseAsRegion(World weWorld, String regionDataJson) {
        Parsed parsed = parse(regionDataJson);
        return switch (parsed.type()) {
            case POLYGON2D -> new Polygonal2DRegion(weWorld, parsed.points(), parsed.minY(), parsed.maxY());
            case CONVEX_POLYHEDRON -> {
                ConvexPolyhedralRegion region = new ConvexPolyhedralRegion(weWorld);
                for (BlockVector3 vertex : parsed.convexVertices()) {
                    region.addVertex(vertex);
                }
                yield region;
            }
            case CUBOID -> new CuboidRegion(weWorld, parsed.pos1(), parsed.pos2());
        };
    }

    /** For re-hydrating into a live {@link com.sk89q.worldedit.LocalSession} - item 6.4's redefine. */
    static RegionSelector parseAsRegionSelector(World weWorld, String regionDataJson) {
        Parsed parsed = parse(regionDataJson);
        return switch (parsed.type()) {
            case POLYGON2D -> new Polygonal2DRegionSelector(weWorld, parsed.points(), parsed.minY(), parsed.maxY());
            case CONVEX_POLYHEDRON -> {
                ConvexPolyhedralRegionSelector selector = new ConvexPolyhedralRegionSelector(weWorld);
                List<BlockVector3> vertices = parsed.convexVertices();
                if (!vertices.isEmpty()) {
                    // selectPrimary clears and sets the first vertex; selectSecondary adds each
                    // remaining one - there's no "give me all vertices at once" constructor for
                    // this selector type (unlike the other two), see ConvexPolyhedralRegionSelector.
                    selector.selectPrimary(vertices.get(0), PermissiveSelectorLimits.getInstance());
                    for (int i = 1; i < vertices.size(); i++) {
                        selector.selectSecondary(vertices.get(i), PermissiveSelectorLimits.getInstance());
                    }
                }
                yield selector;
            }
            case CUBOID -> new CuboidRegionSelector(weWorld, parsed.pos1(), parsed.pos2());
        };
    }

    /**
     * True if the stored region data is a {@code CONVEX_POLYHEDRON} capture - the vertices {@link
     * #extractFootprintVerticesXYZ} returns for this type come from WorldEdit's own {@code
     * Set<BlockVector3>} (unordered), unlike {@code POLYGON2D}/{@code CUBOID}, whose vertices are
     * already in a valid boundary-trace order. Callers projecting a footprint into 2D (u, v) index
     * space (item 6.6, {@code GateLoaderAdapter.precomputeFootprintPolygons}) need to know this so
     * they can re-order the projected points via a 2D convex hull before handing them to {@code
     * GateFrameCalculator.pointInPolygon} - a convex 3D shape's projection onto any plane is
     * itself convex, so this is always safe to do for this type specifically, but would wrongly
     * "fill in" a legitimately concave {@code POLYGON2D} capture if applied there too.
     */
    public static boolean isConvexPolyhedron(String regionDataJson) {
        return parse(regionDataJson).type() == RegionType.CONVEX_POLYHEDRON;
    }

    /**
     * Extracts the footprint's world-space outline vertices. For {@code CONVEX_POLYHEDRON}, each
     * vertex keeps its own real {@code (x, y, z)} - nothing collapses, which is exactly what
     * makes this type able to represent a vertically-standing or diagonally-oriented shape that
     * {@code POLYGON2D}/{@code CUBOID} cannot (see the class Javadoc). For {@code POLYGON2D},
     * every vertex is {@code {x, minY, z}} (the captured X/Z outline has no per-point Y); for
     * {@code CUBOID}, the 4 corners of its bottom face (always world-axis-aligned). A single
     * representative Y is enough for both of those since {@code GateFrameCalculator}'s footprint
     * containment check is 2D (u, v) with depth handled separately via the n-axis check -
     * matching how {@code PLANE_GRID}'s own width/height indices are likewise a flat 2D rectangle
     * in u/v space, not a 3D box directly.
     *
     * <p>No {@link World}/live selection needed - this is pure JSON parsing, usable from a
     * different module ({@code knk-paper}'s {@code gates} package) without a WorldEdit session.
     */
    public static List<double[]> extractFootprintVerticesXYZ(String regionDataJson) {
        Parsed parsed = parse(regionDataJson);
        List<double[]> vertices = new ArrayList<>();

        switch (parsed.type()) {
            case POLYGON2D -> {
                for (BlockVector2 point : parsed.points()) {
                    vertices.add(new double[]{point.x(), parsed.minY(), point.z()});
                }
            }
            case CONVEX_POLYHEDRON -> {
                for (BlockVector3 vertex : parsed.convexVertices()) {
                    vertices.add(new double[]{vertex.x(), vertex.y(), vertex.z()});
                }
            }
            case CUBOID -> {
                BlockVector3 pos1 = parsed.pos1();
                BlockVector3 pos2 = parsed.pos2();
                int minX = Math.min(pos1.x(), pos2.x());
                int maxX = Math.max(pos1.x(), pos2.x());
                int minY = Math.min(pos1.y(), pos2.y());
                int minZ = Math.min(pos1.z(), pos2.z());
                int maxZ = Math.max(pos1.z(), pos2.z());
                vertices.add(new double[]{minX, minY, minZ});
                vertices.add(new double[]{maxX, minY, minZ});
                vertices.add(new double[]{maxX, minY, maxZ});
                vertices.add(new double[]{minX, minY, maxZ});
            }
        }

        return vertices;
    }

    private enum RegionType {
        POLYGON2D, CUBOID, CONVEX_POLYHEDRON
    }

    private static Parsed parse(String regionDataJson) {
        JsonObject json = JsonParser.parseString(regionDataJson).getAsJsonObject();
        String type = json.has("type") ? json.get("type").getAsString() : null;

        if ("POLYGON2D".equals(type)) {
            List<BlockVector2> points = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray("points")) {
                JsonObject p = element.getAsJsonObject();
                points.add(BlockVector2.at(p.get("x").getAsInt(), p.get("z").getAsInt()));
            }
            return Parsed.polygon(points, json.get("minY").getAsInt(), json.get("maxY").getAsInt());
        } else if ("CUBOID".equals(type)) {
            return Parsed.cuboid(jsonToVector(json.getAsJsonObject("pos1")), jsonToVector(json.getAsJsonObject("pos2")));
        } else if ("CONVEX_POLYHEDRON".equals(type)) {
            List<BlockVector3> vertices = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray("points")) {
                vertices.add(jsonToVector(element.getAsJsonObject()));
            }
            return Parsed.convexPolyhedron(vertices);
        }

        throw new IllegalArgumentException("Unknown or missing region type: " + type);
    }

    private static JsonObject vectorToJson(BlockVector3 vector) {
        JsonObject json = new JsonObject();
        json.addProperty("x", vector.x());
        json.addProperty("y", vector.y());
        json.addProperty("z", vector.z());
        return json;
    }

    private static BlockVector3 jsonToVector(JsonObject json) {
        return BlockVector3.at(json.get("x").getAsInt(), json.get("y").getAsInt(), json.get("z").getAsInt());
    }

    private record Parsed(RegionType type, List<BlockVector2> points, int minY, int maxY,
                           BlockVector3 pos1, BlockVector3 pos2, List<BlockVector3> convexVertices) {
        static Parsed polygon(List<BlockVector2> points, int minY, int maxY) {
            return new Parsed(RegionType.POLYGON2D, points, minY, maxY, null, null, null);
        }

        static Parsed cuboid(BlockVector3 pos1, BlockVector3 pos2) {
            return new Parsed(RegionType.CUBOID, null, 0, 0, pos1, pos2, null);
        }

        static Parsed convexPolyhedron(List<BlockVector3> vertices) {
            return new Parsed(RegionType.CONVEX_POLYHEDRON, null, 0, 0, null, null, vertices);
        }
    }
}
