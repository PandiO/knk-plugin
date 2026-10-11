package net.knightsandkings.knk.paper.roads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import org.junit.jupiter.api.Test;

/** DESIGN §5.9: which block changes mark a tile dirty, and the 30 s batch. */
class DirtyTilesTest {

    private static RoadNetworkSnapshot snapshot() {
        return RoadNetworkSnapshot.builder("world")
            .addNode(new RoadNode(1, 0, 64, 0, RoadNodeKind.ENDPOINT, null, 1))
            .addNode(new RoadNode(2, 20, 64, 0, RoadNodeKind.ENDPOINT, null, 1))
            .addEdge(new RoadEdge(10, 1, 2, List.of(new int[] {0, 64, 0}, new int[] {10, 64, 0}, new int[] {20, 64, 0}), 20, 3,
                OptionalInt.empty(), OptionalInt.empty(), 1, Set.of(), List.of(), List.of(), List.of(), RoadEdgeSource.DETECTED, false))
            .build();
    }

    @Test
    void paletteBlocksMatterAnywhere() {
        DirtyTiles dirty = new DirtyTiles();
        dirty.setRoadMaterials(Set.of("STONE_BRICKS", "gravel"));

        assertTrue(dirty.matters("STONE_BRICKS", 5000, 70, -3000));
        assertTrue(dirty.matters("gravel", 1, 1, 1), "material names are case-insensitive");
        assertFalse(dirty.matters("OAK_LOG", 1, 1, 1), "a tree is not a road");
        assertFalse(dirty.matters(null, 1, 1, 1));
    }

    @Test
    void headroomOfARoadSpanMatters() {
        DirtyTiles dirty = new DirtyTiles();
        dirty.setRoadCells(DirtyTiles.RoadCells.of(snapshot()));

        assertEquals(21, dirty.roadCells().size(), "geometry points and the cells between them");
        assertTrue(dirty.matters("STONE", 5, 64, 0), "the road floor itself");
        assertTrue(dirty.matters("STONE", 5, 65, 0), "first headroom block");
        assertTrue(dirty.matters("STONE", 5, 66, 0), "second headroom block");
        assertFalse(dirty.matters("STONE", 5, 67, 0), "above the headroom");
        assertFalse(dirty.matters("STONE", 5, 64, 1), "next to the road");
    }

    @Test
    void marksBatchAndDrain() {
        DirtyTiles dirty = new DirtyTiles();
        dirty.setRoadMaterials(Set.of("STONE_BRICKS"));

        assertTrue(dirty.markIfMatters("world", "STONE_BRICKS", 10, 64, 10));
        assertFalse(dirty.markIfMatters("world", "STONE_BRICKS", 11, 64, 10), "same tile twice = one entry");
        assertFalse(dirty.markIfMatters("world", "DIRT", 600, 64, 10));
        assertTrue(dirty.markIfMatters("world", "STONE_BRICKS", 600, 64, 10));
        assertTrue(dirty.mark("nether", -1, -1));
        assertEquals(3, dirty.pendingCount());

        List<TileKey> batch = dirty.drain();
        assertEquals(3, batch.size());
        assertTrue(batch.contains(new TileKey("world", 0, 0)));
        assertTrue(batch.contains(new TileKey("world", 1, 0)));
        assertTrue(batch.contains(new TileKey("nether", -1, -1)));
        assertEquals(0, dirty.pendingCount());
        assertTrue(dirty.drain().isEmpty());
    }
}
