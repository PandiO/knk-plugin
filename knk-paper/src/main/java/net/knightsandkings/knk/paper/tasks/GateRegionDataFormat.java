package net.knightsandkings.knk.paper.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Polygonal2DRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.regions.RegionSelector;
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector;
import com.sk89q.worldedit.regions.selector.Polygonal2DRegionSelector;
import com.sk89q.worldedit.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the region vertex JSON shape stored in {@code GateDoor.ClosedRegionData}/
 * {@code OpenedRegionData} - documented in {@code WORLDGUARD_REGION_FEASIBILITY.md} §9.1. Shared
 * by {@link GateDoorRegionCaptureHandler} (items 6.3/6.4 - writes it on save, reads it back on
 * redefine) and {@link GateBlockScanTaskHandler} (item 6.5 - reads it for REGION-mode scanning),
 * so the shape has exactly one read implementation and one write implementation, not three
 * independently-drifting copies.
 */
final class GateRegionDataFormat {
    private GateRegionDataFormat() {
    }

    /** Serializes a WorldEdit selection into the §9.1 shape: a polygon (points + Y range) for a
     * {@link Polygonal2DRegion}, or a cuboid (two corner points) for any other region type. */
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
        return parsed.isPolygon()
            ? new Polygonal2DRegion(weWorld, parsed.points(), parsed.minY(), parsed.maxY())
            : new CuboidRegion(weWorld, parsed.pos1(), parsed.pos2());
    }

    /** For re-hydrating into a live {@link com.sk89q.worldedit.LocalSession} - item 6.4's redefine. */
    static RegionSelector parseAsRegionSelector(World weWorld, String regionDataJson) {
        Parsed parsed = parse(regionDataJson);
        return parsed.isPolygon()
            ? new Polygonal2DRegionSelector(weWorld, parsed.points(), parsed.minY(), parsed.maxY())
            : new CuboidRegionSelector(weWorld, parsed.pos1(), parsed.pos2());
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

    private record Parsed(boolean isPolygon, List<BlockVector2> points, int minY, int maxY,
                           BlockVector3 pos1, BlockVector3 pos2) {
        static Parsed polygon(List<BlockVector2> points, int minY, int maxY) {
            return new Parsed(true, points, minY, maxY, null, null);
        }

        static Parsed cuboid(BlockVector3 pos1, BlockVector3 pos2) {
            return new Parsed(false, null, 0, 0, pos1, pos2);
        }
    }
}
