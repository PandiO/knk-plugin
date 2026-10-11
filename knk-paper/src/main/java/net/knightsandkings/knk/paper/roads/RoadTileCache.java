package net.knightsandkings.knk.paper.roads;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.domain.roads.RoadTile;
import net.knightsandkings.knk.core.domain.roads.RoadTileGraph;
import net.knightsandkings.knk.core.domain.roads.RoadTileState;

/**
 * The on-disk tile cache of {@code RoadNetworkCache}: one file per built tile,
 * {@code plugins/KnightsAndKings/roads/<world>/<x>_<z>.json}, holding the tile's ETag and its graph, so a
 * restart downloads only the tiles whose version changed (DESIGN §3.8, §9). Pure Java + Gson (the
 * server bundles Gson), no Bukkit.
 *
 * <p>Format (v1): {@code {"version": 1, "etag": "\"3\"", "tile": {...}, "nodes": [...], "edges": [...]}}
 * with the knk-core records' fields spelled out one by one. A file of another version, or one that
 * doesn't parse, is treated as absent (the tile is downloaded again) - the cache is only a cache.
 */
public final class RoadTileCache {
    public static final int FORMAT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path root;

    /** @param root the {@code roads/} directory (created on first write) */
    public RoadTileCache(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path fileOf(TileKey key) {
        return root.resolve(safeWorld(key.world())).resolve(key.fileName() + ".json");
    }

    /** The cached ETag of a tile, if a readable cache file exists. */
    public Optional<String> etag(TileKey key) {
        return read(key).map(RoadTileGraph::etag);
    }

    /** The cached graph of a tile, or empty when there is none (or it is unreadable / another format). */
    public Optional<RoadTileGraph> read(TileKey key) {
        Path file = fileOf(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.ofNullable(decode(json));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Writes the tile's graph atomically (temp file + move). */
    public void write(RoadTileGraph graph) throws IOException {
        TileKey key = TileKey.of(graph.tile());
        Path file = fileOf(key);
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, encode(graph), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public void delete(TileKey key) throws IOException {
        Files.deleteIfExists(fileOf(key));
    }

    /** Every tile that has a cache file in the world's directory. */
    public List<TileKey> cachedTiles(String world) throws IOException {
        Path dir = root.resolve(safeWorld(world));
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<TileKey> keys = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(path -> {
                String name = path.getFileName().toString();
                if (!name.endsWith(".json")) {
                    return;
                }
                String[] parts = name.substring(0, name.length() - 5).split("_");
                if (parts.length != 2) {
                    return;
                }
                try {
                    keys.add(new TileKey(world, Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
                } catch (NumberFormatException ignored) {
                    // not one of ours
                }
            });
        }
        return keys;
    }

    // ===== codec (package-private for tests) =====

    static String encode(RoadTileGraph graph) {
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.addProperty("etag", graph.etag());
        RoadTile tile = graph.tile();
        JsonObject t = new JsonObject();
        t.addProperty("id", tile.id());
        t.addProperty("world", tile.world());
        t.addProperty("tileX", tile.tileX());
        t.addProperty("tileZ", tile.tileZ());
        t.addProperty("version", tile.version());
        t.addProperty("builtAt", tile.builtAt() == null ? null : tile.builtAt().toString());
        t.addProperty("builderVersion", tile.builderVersion());
        t.addProperty("dirty", tile.dirty());
        t.addProperty("cellCount", tile.cellCount());
        t.addProperty("nodeCount", tile.nodeCount());
        t.addProperty("edgeCount", tile.edgeCount());
        t.addProperty("levelCount", tile.levelCount());
        t.add("warnings", strings(tile.warnings()));
        // Rev. 6 Part B (plan §5.7): optional, so files written before it still decode (as Detected / unconfirmed).
        t.addProperty("state", tile.state().apiName());
        if (tile.curatedAt() != null) {
            t.addProperty("curatedAt", tile.curatedAt().toString());
        }
        root.add("tile", t);

        JsonArray nodes = new JsonArray();
        for (RoadNode n : graph.nodes()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", n.id());
            o.addProperty("x", n.x());
            o.addProperty("y", n.y());
            o.addProperty("z", n.z());
            o.addProperty("kind", n.kind().apiName());
            if (n.name() != null) {
                o.addProperty("name", n.name());
            }
            o.addProperty("componentId", n.componentId());
            o.addProperty("locked", n.locked());
            if (n.isPlazaCentre()) {
                o.addProperty("plazaRadius", n.plazaRadius());
            }
            nodes.add(o);
        }
        root.add("nodes", nodes);

        JsonArray edges = new JsonArray();
        for (RoadEdge e : graph.edges()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id());
            o.addProperty("from", e.fromNodeId());
            o.addProperty("to", e.toNodeId());
            JsonArray geometry = new JsonArray();
            for (int[] p : e.geometry()) {
                JsonArray point = new JsonArray();
                point.add(p[0]);
                point.add(p[1]);
                point.add(p[2]);
                geometry.add(point);
            }
            o.add("geometry", geometry);
            o.addProperty("length", e.length());
            o.addProperty("avgWidth", e.avgWidth());
            if (e.profileId().isPresent()) {
                o.addProperty("profileId", e.profileId().getAsInt());
            }
            if (e.streetId().isPresent()) {
                o.addProperty("streetId", e.streetId().getAsInt());
            }
            o.addProperty("costMultiplier", e.costMultiplier());
            JsonArray flags = new JsonArray();
            for (RoadEdgeFlag f : e.flags()) {
                flags.add(f.apiName());
            }
            o.add("flags", flags);
            o.add("gateDoorIds", ints(e.gateDoorIds()));
            o.add("domainIds", ints(e.domainIds()));
            o.add("regionIds", strings(e.regionIds()));
            o.addProperty("source", e.source().apiName());
            o.addProperty("stale", e.stale());
            if (e.confirmed()) {
                o.addProperty("confirmed", true);
            }
            edges.add(o);
        }
        root.add("edges", edges);
        return GSON.toJson(root);
    }

    /** @return the graph, or null when the file is of another format version */
    static RoadTileGraph decode(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("version") || root.get("version").getAsInt() != FORMAT_VERSION) {
            return null;
        }
        JsonObject t = root.getAsJsonObject("tile");
        RoadTile tile = new RoadTile(
            t.get("id").getAsInt(), t.get("world").getAsString(), t.get("tileX").getAsInt(), t.get("tileZ").getAsInt(),
            t.get("version").getAsInt(),
            t.has("builtAt") && !t.get("builtAt").isJsonNull() ? OffsetDateTime.parse(t.get("builtAt").getAsString()) : null,
            t.get("builderVersion").getAsInt(), t.get("dirty").getAsBoolean(), t.get("cellCount").getAsInt(),
            t.get("nodeCount").getAsInt(), t.get("edgeCount").getAsInt(), t.get("levelCount").getAsInt(),
            stringList(t.getAsJsonArray("warnings")),
            RoadTileState.fromApiName(t.has("state") ? t.get("state").getAsString() : null),
            t.has("curatedAt") ? OffsetDateTime.parse(t.get("curatedAt").getAsString()) : null);

        List<RoadNode> nodes = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("nodes")) {
            JsonObject o = el.getAsJsonObject();
            nodes.add(new RoadNode(o.get("id").getAsInt(), o.get("x").getAsInt(), o.get("y").getAsInt(),
                o.get("z").getAsInt(), RoadNodeKind.fromApiName(o.get("kind").getAsString()),
                o.has("name") ? o.get("name").getAsString() : null, o.get("componentId").getAsInt(),
                o.has("locked") && o.get("locked").getAsBoolean(),
                o.has("plazaRadius") ? o.get("plazaRadius").getAsInt() : 0));
        }

        List<RoadEdge> edges = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("edges")) {
            JsonObject o = el.getAsJsonObject();
            List<int[]> geometry = new ArrayList<>();
            for (JsonElement p : o.getAsJsonArray("geometry")) {
                JsonArray a = p.getAsJsonArray();
                geometry.add(new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
            }
            Set<RoadEdgeFlag> flags = EnumSet.noneOf(RoadEdgeFlag.class);
            for (JsonElement f : o.getAsJsonArray("flags")) {
                flags.add(RoadEdgeFlag.fromApiName(f.getAsString()));
            }
            edges.add(new RoadEdge(o.get("id").getAsInt(), o.get("from").getAsInt(), o.get("to").getAsInt(), geometry,
                o.get("length").getAsDouble(), o.get("avgWidth").getAsDouble(),
                o.has("profileId") ? OptionalInt.of(o.get("profileId").getAsInt()) : OptionalInt.empty(),
                o.has("streetId") ? OptionalInt.of(o.get("streetId").getAsInt()) : OptionalInt.empty(),
                o.get("costMultiplier").getAsDouble(), flags, intList(o.getAsJsonArray("gateDoorIds")),
                intList(o.getAsJsonArray("domainIds")), stringList(o.getAsJsonArray("regionIds")),
                RoadEdgeSource.fromApiName(o.get("source").getAsString()), o.get("stale").getAsBoolean(),
                o.has("confirmed") && o.get("confirmed").getAsBoolean()));
        }
        return new RoadTileGraph(tile, nodes, edges);
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static JsonArray ints(List<Integer> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static List<String> stringList(JsonArray array) {
        List<String> list = new ArrayList<>();
        if (array != null) {
            array.forEach(el -> list.add(el.getAsString()));
        }
        return list;
    }

    private static List<Integer> intList(JsonArray array) {
        List<Integer> list = new ArrayList<>();
        if (array != null) {
            array.forEach(el -> list.add(el.getAsInt()));
        }
        return list;
    }

    /** World names are file-system safe on every server we know of, but never trust a path separator. */
    static String safeWorld(String world) {
        return world.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
