package net.knightsandkings.knk.paper.roads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.domain.roads.RoadTile;
import net.knightsandkings.knk.core.domain.roads.RoadTileGraph;
import net.knightsandkings.knk.core.domain.roads.RoadTileState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The tile file cache: round trip, ETag, atomic replace, unreadable files ignored. */
class RoadTileCacheTest {

    @TempDir
    Path dir;

    static RoadTileGraph graph(int version) {
        RoadTile tile = new RoadTile(5, "world", 2, -3, version, OffsetDateTime.of(2026, 9, 28, 10, 0, 0, 0, ZoneOffset.UTC),
            1, false, 1200, 2, 1, 1, List.of("Cell cap reached; the mask is incomplete at 10,64,10"),
            RoadTileState.CURATED, OffsetDateTime.of(2026, 10, 5, 8, 0, 0, 0, ZoneOffset.UTC));
        RoadNode a = new RoadNode(100, 1024, 64, -1536, RoadNodeKind.JUNCTION, "Market", 3, true, 12); // a designed plaza
        RoadNode b = new RoadNode(101, 1060, 66, -1500, RoadNodeKind.BOUNDARY, null, 3);
        RoadEdge e = new RoadEdge(200, 100, 101, List.of(new int[] {1024, 64, -1536}, new int[] {1040, 65, -1520}, new int[] {1060, 66, -1500}),
            51.2, 3.4, OptionalInt.of(1), OptionalInt.of(7), 1.25, EnumSet.of(RoadEdgeFlag.ONEWAY, RoadEdgeFlag.NO_GPS),
            List.of(9), List.of(12, 13), List.of("kardenna", "kardenna_market"), RoadEdgeSource.DETECTED, true, true);
        return new RoadTileGraph(tile, List.of(a, b), List.of(e));
    }

    @Test
    void roundTripsEveryField() {
        RoadTileGraph original = graph(3);
        RoadTileGraph decoded = RoadTileCache.decode(RoadTileCache.encode(original));

        assertEquals(original.tile(), decoded.tile());
        assertEquals(original.nodes(), decoded.nodes());
        assertEquals(original.etag(), decoded.etag());
        RoadEdge e = decoded.edges().get(0);
        RoadEdge o = original.edges().get(0);
        assertEquals(o.id(), e.id());
        assertEquals(o.fromNodeId(), e.fromNodeId());
        assertEquals(o.toNodeId(), e.toNodeId());
        assertEquals(3, e.geometry().size());
        assertEquals(1040, e.geometry().get(1)[0]);
        assertEquals(o.length(), e.length());
        assertEquals(o.avgWidth(), e.avgWidth());
        assertEquals(o.profileId(), e.profileId());
        assertEquals(o.streetId(), e.streetId());
        assertEquals(o.costMultiplier(), e.costMultiplier());
        assertEquals(o.flags(), e.flags());
        assertEquals(o.gateDoorIds(), e.gateDoorIds());
        assertEquals(o.domainIds(), e.domainIds());
        assertEquals(o.regionIds(), e.regionIds());
        assertEquals(o.source(), e.source());
        assertEquals(o.stale(), e.stale());
        assertTrue(e.confirmed());
    }

    @Test
    void aFileWrittenBeforeCuratedTilesDecodesAsDetectedAndUnconfirmed() {
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(RoadTileCache.encode(graph(3))).getAsJsonObject();
        root.getAsJsonObject("tile").remove("state");
        root.getAsJsonObject("tile").remove("curatedAt");
        root.getAsJsonArray("edges").get(0).getAsJsonObject().remove("confirmed");
        RoadTileGraph decoded = RoadTileCache.decode(root.toString());

        assertEquals(RoadTileState.DETECTED, decoded.tile().state());
        assertEquals(null, decoded.tile().curatedAt());
        assertFalse(decoded.edges().get(0).confirmed());
    }

    @Test
    void writesReadsListsAndDeletesFiles() throws Exception {
        RoadTileCache cache = new RoadTileCache(dir);
        TileKey key = new TileKey("world", 2, -3);
        assertTrue(cache.read(key).isEmpty());

        cache.write(graph(1));
        assertEquals(Optional.of("\"1\""), cache.etag(key));
        assertTrue(Files.isRegularFile(dir.resolve("world").resolve("2_-3.json")));
        cache.write(graph(2));
        assertEquals(Optional.of("\"2\""), cache.etag(key), "a rewrite replaces the file");
        assertFalse(Files.exists(dir.resolve("world").resolve("2_-3.json.tmp")));
        assertEquals(List.of(key), cache.cachedTiles("world"));

        cache.delete(key);
        assertTrue(cache.read(key).isEmpty());
        assertTrue(cache.cachedTiles("world").isEmpty());
        assertTrue(cache.cachedTiles("no-such-world").isEmpty());
    }

    @Test
    void unreadableOrForeignFilesCountAsAbsent() throws Exception {
        RoadTileCache cache = new RoadTileCache(dir);
        Files.createDirectories(dir.resolve("world"));
        Files.writeString(dir.resolve("world").resolve("0_0.json"), "{not json");
        Files.writeString(dir.resolve("world").resolve("1_0.json"), "{\"version\": 99}");
        Files.writeString(dir.resolve("world").resolve("notes.json"), "{}");

        assertTrue(cache.read(new TileKey("world", 0, 0)).isEmpty());
        assertTrue(cache.read(new TileKey("world", 1, 0)).isEmpty());
        assertEquals(2, cache.cachedTiles("world").size(), "only <x>_<z>.json names are tiles");
        assertEquals("my_world", RoadTileCache.safeWorld("my/world"));
    }
}
