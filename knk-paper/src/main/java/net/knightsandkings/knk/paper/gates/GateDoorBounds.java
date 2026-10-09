package net.knightsandkings.knk.paper.gates;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.gates.target.GateBox;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.logging.Logger;

/**
 * A gate door's "region" for the gate command targets (KNG-78 {@code here}, KNG-79 look-at): the
 * box spanned by
 * <ul>
 *   <li>its door blocks in the <em>closed</em> position ({@link GateManager#closedFootprint}),
 *       whatever its current state - so an open gate, whose opening holds no blocks, still has its
 *       opening as its region;</li>
 *   <li>its captured closed/opened regions ({@code ClosedRegionData}/{@code OpenedRegionData},
 *       the {@code GateRegionDataFormat} JSON), when present - which also covers where an opened
 *       drawbridge or swung door rests.</li>
 * </ul>
 * Doors are not WorldGuard regions themselves; this box is what the issues' "the door's region"
 * means in code. A door with neither falls back to its anchor block. The region JSON is read with
 * Gson here rather than through {@code GateRegionDataFormat} (WorldEdit types) so the box needs no
 * WorldEdit at runtime or in tests.
 */
public final class GateDoorBounds {
    private static final Logger LOGGER = Logger.getLogger(GateDoorBounds.class.getName());

    private GateDoorBounds() {
    }

    public static GateBox of(CachedGateDoor door, GateManager gateManager) {
        GateBox box = null;
        List<Vector> closed = gateManager.closedFootprint(door.getId());
        for (Vector block : closed) {
            box = GateBox.ofBlock(block.getBlockX(), block.getBlockY(), block.getBlockZ()).union(box);
        }
        box = union(box, regionBox(door.getClosedRegionData(), door.getId()));
        box = union(box, regionBox(door.getOpenedRegionData(), door.getId()));
        if (box == null && door.getAnchorPoint() != null) {
            Vector anchor = door.getAnchorPoint();
            box = GateBox.ofBlock(anchor.getBlockX(), anchor.getBlockY(), anchor.getBlockZ());
        }
        return box;
    }

    private static GateBox union(GateBox a, GateBox b) {
        if (a == null) {
            return b;
        }
        return a.union(b);
    }

    /**
     * The block-corner box of a stored region (CUBOID pos1/pos2, POLYGON2D points + minY/maxY,
     * CONVEX_POLYHEDRON points), or null when there is none or it can't be read.
     */
    static GateBox regionBox(String regionDataJson, int doorId) {
        if (regionDataJson == null || regionDataJson.isBlank()) {
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(regionDataJson).getAsJsonObject();
            String type = json.has("type") ? json.get("type").getAsString() : "CUBOID";
            return switch (type) {
                case "POLYGON2D" -> {
                    GateBox box = null;
                    int minY = json.get("minY").getAsInt();
                    int maxY = json.get("maxY").getAsInt();
                    for (JsonElement element : json.getAsJsonArray("points")) {
                        JsonObject p = element.getAsJsonObject();
                        int x = p.get("x").getAsInt();
                        int z = p.get("z").getAsInt();
                        box = new GateBox(x, Math.min(minY, maxY), z, x + 1, Math.max(minY, maxY) + 1, z + 1).union(box);
                    }
                    yield box;
                }
                case "CONVEX_POLYHEDRON" -> {
                    GateBox box = null;
                    for (JsonElement element : json.getAsJsonArray("points")) {
                        box = blockOf(element.getAsJsonObject()).union(box);
                    }
                    yield box;
                }
                default -> blockOf(json.getAsJsonObject("pos1")).union(blockOf(json.getAsJsonObject("pos2")));
            };
        } catch (RuntimeException ex) {
            LOGGER.fine("Gate door #" + doorId + ": unreadable region data ignored for command targeting: " + ex.getMessage());
            return null;
        }
    }

    private static GateBox blockOf(JsonObject point) {
        return GateBox.ofBlock(point.get("x").getAsInt(), point.get("y").getAsInt(), point.get("z").getAsInt());
    }
}
