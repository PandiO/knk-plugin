package net.knightsandkings.knk.paper.roads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import net.knightsandkings.knk.core.domain.roads.RoadMaterialRole;
import net.knightsandkings.knk.core.roads.build.BuildParameters;
import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.build.MaskBuilder;
import net.knightsandkings.knk.core.roads.build.NodeMatcher;
import net.knightsandkings.knk.core.roads.build.PassabilityRules;
import net.knightsandkings.knk.core.roads.build.ProfileSet;
import net.knightsandkings.knk.core.roads.build.TileBuildResult;
import net.knightsandkings.knk.core.roads.build.TileBuilder;
import net.knightsandkings.knk.core.roads.survey.ProposedProfile;
import net.knightsandkings.knk.core.util.BlockKey;
import org.junit.jupiter.api.Test;

/**
 * The compact extraction (DESIGN §9) answers the builder exactly where the builder asks: a fake world is
 * extracted chunk by chunk, the real {@link TileBuilder} runs on the grid, and the grid counts every query
 * outside the described cells. Bukkit-free (the scratch build runs it).
 */
class CompactSurfaceGridTest {

    /** A tiny world: grass ground at y = 60 everywhere, air above, and whatever the test places. */
    static final class FakeWorld implements SpanExtractor.BlockSource, CrossSectionSampler.Blocks {
        final Map<Long, String> blocks = new HashMap<>();
        final int groundY;

        FakeWorld(int groundY) {
            this.groundY = groundY;
        }

        void set(int x, int y, int z, String material) {
            blocks.put(BlockKey.pack(x, y, z), material);
        }

        @Override
        public String materialAt(int x, int y, int z) {
            String m = blocks.get(BlockKey.pack(x, y, z));
            if (m != null) {
                return m;
            }
            if (y == groundY) {
                return "GRASS_BLOCK";
            }
            return y < groundY ? "STONE" : "AIR";
        }

        /** The chunk-local view the extractor sees. */
        SpanExtractor.BlockSource chunk(int chunkX, int chunkZ) {
            return (lx, y, lz) -> materialAt((chunkX << 4) + lx, y, (chunkZ << 4) + lz);
        }
    }

    static ProfileSet.Profile townRoad() {
        return new ProfileSet.Profile(1, "Town road", true, 2, 5, Set.of(), List.of(
            new ProposedProfile.Material("STONE_BRICKS", RoadMaterialRole.SURFACE, false, 0.8, 0.1, 100),
            new ProposedProfile.Material("STONE_BRICK_SLAB", RoadMaterialRole.ACCENT, false, 0.05, 0.05, 5)));
    }

    static PassabilityRules rules() {
        return PassabilityRules.of(PassabilityRules::curatedCollidable);
    }

    static CompactSurfaceGrid capture(FakeWorld world, GateCells gates, int chunkMin, int chunkMax) {
        CompactSurfaceGrid grid = new CompactSurfaceGrid(rules(), townRoad()::isFloorMaterial, gates, -64, 320);
        for (int cx = chunkMin; cx <= chunkMax; cx++) {
            for (int cz = chunkMin; cz <= chunkMax; cz++) {
                grid.capture(world.chunk(cx, cz), cx, cz);
            }
        }
        return grid;
    }

    @Test
    void extractsOnlyProfileFloorsWithHeadroom() {
        FakeWorld world = new FakeWorld(60);
        for (int x = 0; x < 20; x++) {
            world.set(x, 60, 5, "STONE_BRICKS");
        }
        world.set(10, 61, 5, "STONE"); // a block on the road: no headroom
        world.set(15, 60, 5, "LAVA");   // a hazard floor is never a span
        world.set(3, 60, 8, "STONE_BRICKS");
        world.set(3, 63, 8, "STONE"); // headroom of two, third block blocked (no step up from here)

        CompactSurfaceGrid grid = capture(world, GateCells.NONE, 0, 1);

        assertTrue(grid.isSolid(0, 60, 5));
        assertTrue(grid.isPassable(0, 61, 5));
        assertTrue(grid.isPassable(0, 62, 5));
        assertTrue(grid.isPassable(0, 63, 5), "third block above is air");
        assertFalse(grid.isSolid(10, 60, 5), "no headroom → not a span");
        assertFalse(grid.isSolid(15, 60, 5), "lava is not a span");
        assertFalse(grid.isSolid(0, 60, 6), "grass is not a profile material");
        assertEquals("STONE_BRICKS", grid.floorMaterial(0, 60, 5));
        assertTrue(grid.isSolid(3, 60, 8));
        assertFalse(grid.isPassable(3, 63, 8), "third block above blocked, flagged");
        assertThrows(IllegalStateException.class, () -> grid.floorMaterial(0, 60, 6));
        assertEquals(19, grid.spans().spanCount(), "20 road cells − the blocked one − the lava one + the one at z = 8");
    }

    @Test
    void stairsAndGateCellsAreFlagged() {
        FakeWorld world = new FakeWorld(60);
        world.set(1, 60, 1, "STONE_BRICK_SLAB");
        world.set(2, 60, 1, "STONE_BRICKS");
        world.set(2, 61, 1, "OAK_PLANKS"); // a closed gate door block on the road
        GateCells gates = (x, y, z) -> x == 2 && y == 61 && z == 1 ? OptionalInt.of(7) : OptionalInt.empty();

        CompactSurfaceGrid grid = capture(world, gates, 0, 0);

        assertTrue(grid.isStairOrSlab(1, 60, 1));
        assertFalse(grid.isStairOrSlab(2, 60, 1));
        assertTrue(grid.isSolid(2, 60, 1), "a door block counts as passable headroom");
        assertTrue(grid.spans().hasFlag(2, 60, 1, CompactSpans.FLAG_GATE));
    }

    @Test
    void theRealBuilderNeverAsksOutsideTheExtraction() {
        FakeWorld world = new FakeWorld(60);
        // A 3-wide stone-brick road along x, with a closed gate across it at x = 20.
        for (int x = 0; x < 48; x++) {
            for (int z = 10; z <= 12; z++) {
                world.set(x, 60, z, "STONE_BRICKS");
            }
        }
        for (int z = 10; z <= 12; z++) {
            world.set(20, 61, z, "OAK_PLANKS");
            world.set(20, 62, z, "OAK_PLANKS");
        }
        GateCells gates = (x, y, z) -> x == 20 && (y == 61 || y == 62) && z >= 10 && z <= 12 ? OptionalInt.of(7) : OptionalInt.empty();
        CompactSurfaceGrid grid = capture(world, gates, 0, 3);

        BuildParameters params = BuildParameters.defaults().withTile(64, 8);
        TileBuilder.TileRequest request = new TileBuilder.TileRequest("world", 0, 0, params,
            List.of(new MaskBuilder.Seed(2, 60, 11)), new ProfileSet(List.of(townRoad())), gates, List.of(),
            NodeMatcher.PreviousGraph.EMPTY);
        TileBuildResult result = new TileBuilder().build(request, grid);

        assertTrue(result.cellCount() >= 140, "every road cell reached, got " + result.cellCount());
        assertTrue(result.nodes().size() >= 2, "two endpoints at least");
        assertFalse(result.edges().isEmpty());
        assertTrue(result.edges().stream().anyMatch(e -> e.gateDoorIds().contains(7)), "the edge through the gate names door 7");
        assertEquals(0, grid.unknownQueries(), "the builder asked about cells the extraction doesn't describe");
    }

    @Test
    void frontierChunksAreTheUncapturedNeighboursOfBorderSpans() {
        FakeWorld world = new FakeWorld(60);
        for (int x = 0; x < 16; x++) {
            world.set(x, 60, 3, "STONE_BRICKS"); // touches the west and east borders of chunk 0,0
        }
        CompactSurfaceGrid grid = new CompactSurfaceGrid(rules(), townRoad()::isFloorMaterial, GateCells.NONE, -64, 320);
        grid.capture(world.chunk(0, 0), 0, 0);

        Set<Long> frontier = grid.spans().frontierChunks(-100, -100, 100, 100);

        // the road leaves through the west and east borders only, so no diagonal neighbour is needed
        assertEquals(Set.of(CompactSpans.chunkKey(-1, 0), CompactSpans.chunkKey(1, 0)), frontier);
        grid.captureEmpty(1, 0);
        assertFalse(grid.spans().frontierChunks(-100, -100, 100, 100).contains(CompactSpans.chunkKey(1, 0)), "captured chunks leave the frontier");
        assertTrue(grid.spans().frontierChunks(0, 0, 15, 15).isEmpty(), "nothing outside the region");
    }
}
