package net.knightsandkings.knk.paper.navigation.walk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.build.PassabilityRules;
import net.knightsandkings.knk.core.roads.build.SurfaceGrid;
import net.knightsandkings.knk.core.roads.walk.MovementProfile;
import net.knightsandkings.knk.core.roads.walk.WalkCells;
import net.knightsandkings.knk.core.roads.walk.WalkRequest;
import net.knightsandkings.knk.core.roads.walk.WalkResult;
import net.knightsandkings.knk.core.roads.walk.WalkSearch;
import net.knightsandkings.knk.core.roads.walk.WalkTerrain;
import net.knightsandkings.knk.core.util.BlockKey;
import net.knightsandkings.knk.paper.roads.SpanExtractor;
import org.junit.jupiter.api.Test;

/**
 * The walk capture (KNG-51 §8, Phase B) answers the search exactly like the world it was captured
 * from: a fake world is captured chunk by chunk and compared, block by block and search by search,
 * with a reference {@link SurfaceGrid} + {@link WalkCells#ofMaterials} reading the same world directly.
 * Bukkit-free.
 */
class WalkCaptureTest {

    static final int MIN_Y = -64;
    static final int MAX_Y = 320;

    /** Grass at y = 64 everywhere, stone below, air above, plus whatever the test places. */
    static final class FakeWorld implements SpanExtractor.BlockSource {
        final Map<Long, String> blocks = new HashMap<>();
        final Map<Long, Integer> gateCells = new HashMap<>();
        int groundY = 64;

        FakeWorld set(int x, int y, int z, String material) {
            blocks.put(BlockKey.pack(x, y, z), material);
            return this;
        }

        FakeWorld column(int x, int z, int y0, int y1, String material) {
            for (int y = y0; y <= y1; y++) {
                set(x, y, z, material);
            }
            return this;
        }

        String at(int x, int y, int z) {
            String m = blocks.get(BlockKey.pack(x, y, z));
            if (m != null) {
                return m;
            }
            if (y == groundY) {
                return "GRASS_BLOCK";
            }
            return y < groundY ? "STONE" : "AIR";
        }

        @Override
        public String materialAt(int x, int y, int z) {
            return at(x, y, z);
        }

        SpanExtractor.BlockSource chunk(int chunkX, int chunkZ) {
            return (lx, y, lz) -> at((chunkX << 4) + lx, y, (chunkZ << 4) + lz);
        }

        GateCells gates() {
            return (x, y, z) -> {
                Integer id = gateCells.get(BlockKey.pack(x, y, z));
                return id == null ? OptionalInt.empty() : OptionalInt.of(id);
            };
        }
    }

    /** The reference: the same rules read straight from the world. */
    static final class DirectGrid implements SurfaceGrid {
        final FakeWorld world;
        final PassabilityRules rules;

        DirectGrid(FakeWorld world, PassabilityRules rules) {
            this.world = world;
            this.rules = rules;
        }

        @Override
        public boolean isPassable(int x, int y, int z) {
            return rules.isPassable(world.at(x, y, z));
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            return rules.isSolid(world.at(x, y, z));
        }

        @Override
        public boolean isHazard(int x, int y, int z) {
            return rules.isHazard(world.at(x, y, z));
        }

        @Override
        public int minY() {
            return MIN_Y;
        }

        @Override
        public int maxY() {
            return MAX_Y;
        }

        @Override
        public String floorMaterial(int x, int y, int z) {
            String m = world.at(x, y, z);
            return rules.isOverlay(m) ? world.at(x, y - 1, z) : m;
        }

        @Override
        public boolean isStairOrSlab(int x, int y, int z) {
            return PassabilityRules.isStairOrSlab(world.at(x, y, z));
        }
    }

    static PassabilityRules rules() {
        return PassabilityRules.of(PassabilityRules::curatedCollidable);
    }

    /** Paper's collision flag: ladders (and doors) are collidable, unlike in the curated table. */
    static PassabilityRules paperRules() {
        return PassabilityRules.of(m -> m.equals("LADDER") || PassabilityRules.curatedCollidable(m));
    }

    static WalkChunkExtractor extractor(FakeWorld world) {
        return extractor(world, rules());
    }

    static WalkChunkExtractor extractor(FakeWorld world, PassabilityRules rules) {
        return new WalkChunkExtractor(rules, Set.of("LADDER"), world.gates(), MIN_Y, MAX_Y);
    }

    static CapturedWalkTerrain capture(FakeWorld world, int chunkMin, int chunkMax, int fromSection, int toSection) {
        return capture(world, rules(), chunkMin, chunkMax, fromSection, toSection);
    }

    static CapturedWalkTerrain capture(FakeWorld world, PassabilityRules rules, int chunkMin, int chunkMax,
                                       int fromSection, int toSection) {
        WalkChunkExtractor extractor = extractor(world, rules);
        List<WalkChunk> chunks = new ArrayList<>();
        for (int cx = chunkMin; cx <= chunkMax; cx++) {
            for (int cz = chunkMin; cz <= chunkMax; cz++) {
                chunks.add(extractor.extract(world.chunk(cx, cz), cx, cz, fromSection, toSection, s -> false, 0L));
            }
        }
        return new CapturedWalkTerrain(chunks, world.gates(), MIN_Y, MAX_Y);
    }

    /**
     * A small village corner: a house with an oak door and glass panes, a fenced garden, a cobblestone
     * wall, a 4-high cliff with a ladder, a 3-block drop, a pond, stairs and a slab, carpet, lava, an
     * iron door, an open gate and a closed gate (iron bars).
     */
    static FakeWorld village() {
        FakeWorld w = new FakeWorld();
        // house walls x 4..8, z 4..8, door at (6, 65..66, 4), panes at (4, 66, 6)
        for (int x = 4; x <= 8; x++) {
            for (int z = 4; z <= 8; z++) {
                boolean wall = x == 4 || x == 8 || z == 4 || z == 8;
                if (wall) {
                    w.column(x, z, 65, 67, "OAK_PLANKS");
                }
            }
        }
        w.column(6, 4, 65, 66, "OAK_DOOR");
        w.set(4, 66, 6, "GLASS_PANE");
        w.set(6, 65, 6, "RED_CARPET");
        // fence around a garden x 10..13, z 0..3 with a fence gate
        for (int x = 10; x <= 13; x++) {
            w.set(x, 65, 0, "OAK_FENCE").set(x, 65, 3, "OAK_FENCE");
        }
        w.set(10, 65, 1, "OAK_FENCE").set(10, 65, 2, "OAK_FENCE_GATE");
        w.set(13, 65, 1, "OAK_FENCE").set(13, 65, 2, "OAK_FENCE");
        // a cobblestone wall line
        for (int z = 10; z <= 14; z++) {
            w.set(2, 65, z, "COBBLESTONE_WALL");
        }
        // a 4-high cliff at x >= 20 with a ladder on its face at (19, 65..68, 5)
        for (int x = 20; x <= 26; x++) {
            for (int z = 0; z <= 12; z++) {
                w.column(x, z, 65, 68, "STONE");
            }
        }
        w.column(19, 5, 65, 68, "LADDER");
        // a 3-block drop: a ledge x 14..16 z 14..16 at y 67 (two blocks of dirt on grass)
        for (int x = 14; x <= 16; x++) {
            for (int z = 14; z <= 16; z++) {
                w.column(x, z, 65, 67, "DIRT");
            }
        }
        // a pond: water at y 64 replacing the grass, x 0..2 z 18..20 (wading), one deep column
        for (int x = 0; x <= 2; x++) {
            for (int z = 18; z <= 20; z++) {
                w.set(x, 64, z, "WATER");
                w.set(x, 63, z, "SAND");
            }
        }
        w.set(1, 63, 19, "WATER").set(1, 62, 19, "SAND");
        // stairs and a slab up to a platform at y 66
        w.set(10, 65, 10, "OAK_STAIRS").set(11, 65, 10, "STONE_SLAB").column(12, 10, 65, 66, "STONE");
        // lava, an iron door, an open gate (no blocks, footprint registered) and a closed one (iron bars)
        w.set(28, 64, 20, "LAVA");
        w.column(28, 24, 65, 66, "IRON_DOOR");
        w.gateCells.put(BlockKey.pack(5, 65, 24), 7);
        w.gateCells.put(BlockKey.pack(5, 66, 24), 7);
        w.column(6, 24, 65, 66, "IRON_BARS");
        w.gateCells.put(BlockKey.pack(6, 65, 24), 8);
        w.gateCells.put(BlockKey.pack(6, 66, 24), 8);
        // a tree trunk and leaves, a cave pocket under the grass
        w.column(14, 24, 65, 69, "OAK_LOG");
        w.set(13, 69, 24, "OAK_LEAVES").set(15, 69, 24, "OAK_LEAVES").set(14, 70, 24, "OAK_LEAVES");
        w.column(24, 24, 58, 60, "AIR");
        return w;
    }

    @Test
    void everyCapturedBlockAnswersLikeTheWorld() {
        everyCapturedBlockAnswersLikeTheWorld(rules());
        everyCapturedBlockAnswersLikeTheWorld(paperRules());
    }

    private static void everyCapturedBlockAnswersLikeTheWorld(PassabilityRules rules) {
        FakeWorld world = village();
        CapturedWalkTerrain captured = capture(world, rules, 0, 1, 3, 4); // y 48..79
        DirectGrid direct = new DirectGrid(world, rules);
        WalkCells reference = WalkCells.ofMaterials(world::at, Set.of("LADDER"));

        int floors = 0;
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                for (int y = 48; y <= 79; y++) {
                    String where = x + "," + y + "," + z + " " + world.at(x, y, z);
                    assertEquals(direct.isPassable(x, y, z), captured.isPassable(x, y, z), "passable " + where);
                    assertEquals(direct.isSolid(x, y, z), captured.isSolid(x, y, z), "solid " + where);
                    assertEquals(direct.isHazard(x, y, z), captured.isHazard(x, y, z), "hazard " + where);
                    assertEquals(direct.isStairOrSlab(x, y, z), captured.isStairOrSlab(x, y, z), "stair " + where);
                    assertEquals(reference.isDoor(x, y, z), captured.isDoor(x, y, z), "door " + where);
                    assertEquals(reference.isClimbable(x, y, z), captured.isClimbable(x, y, z), "climbable " + where);
                    assertEquals(reference.isWater(x, y, z), captured.isWater(x, y, z), "water " + where);
                    boolean candidate = direct.isSolid(x, y, z) && !direct.isHazard(x, y, z)
                        && (direct.isPassable(x, y + 1, z) || reference.isDoor(x, y + 1, z)
                        || reference.isClimbable(x, y + 1, z) || world.gates().doorAt(x, y + 1, z).isPresent());
                    if (candidate) {
                        floors++;
                        assertEquals(direct.floorMaterial(x, y, z), captured.floorMaterial(x, y, z), "floor " + where);
                    }
                }
            }
        }
        assertTrue(floors > 32 * 32, "every column has its ground floor, plus roofs, ledges, platforms");
        assertEquals(0, captured.outsideQueries());
    }

    @Test
    void searchesOverTheCaptureEqualSearchesOverTheWorld() {
        searchesOverTheCaptureEqualSearchesOverTheWorld(rules());
        searchesOverTheCaptureEqualSearchesOverTheWorld(paperRules());
    }

    private static void searchesOverTheCaptureEqualSearchesOverTheWorld(PassabilityRules rules) {
        FakeWorld world = village();
        CapturedWalkTerrain captured = capture(world, rules, 0, 1, 3, 4);
        DirectGrid direct = new DirectGrid(world, rules);
        WalkTerrain reference = new WalkTerrain(direct, world.gates(), WalkCells.ofMaterials(world::at, Set.of("LADDER")));
        WalkTerrain fromCapture = captured.terrain();

        int[][] legs = {
            {1, 64, 1, 6, 64, 6},     // into the house through the door
            {1, 64, 1, 18, 64, 5},    // to the foot of the ladder
            {17, 64, 5, 22, 68, 5},   // up the ladder onto the cliff
            {22, 68, 6, 17, 64, 6},   // down again (ladder or nothing)
            {15, 67, 15, 15, 64, 19}, // off the ledge (a 3-block drop)
            {11, 64, 1, 12, 64, 2},   // into the fenced garden through the gate
            {1, 64, 16, 1, 64, 22},   // through the pond
            {9, 64, 10, 12, 66, 10},  // up the stairs and the slab
            {5, 64, 22, 5, 64, 26},   // through the open gate's footprint
            {3, 64, 12, 1, 64, 12},   // across the wall line
            {27, 64, 20, 29, 64, 20}, // around the lava
            {28, 64, 23, 28, 64, 25}, // the iron door is never a way
        };
        for (int[] leg : legs) {
            WalkRequest base = WalkRequest.toPoint(reference, leg[0] + 0.5, leg[1] + 1.0, leg[2] + 0.5,
                leg[3] + 0.5, leg[4], leg[5] + 0.5, 0.25).withProfile(MovementProfile.PLAYER);
            WalkResult want = new WalkSearch().find(base);
            WalkResult got = new WalkSearch().find(new WalkRequest(fromCapture, base.access(), base.profile(),
                base.startX(), base.startY(), base.startZ(), base.targetX(), base.targetFloorY(), base.targetZ(),
                base.goal(), base.budget()));
            String name = java.util.Arrays.toString(leg);
            assertEquals(want.status(), got.status(), name + " " + got);
            if (leg == legs[0] || leg == legs[2] || leg == legs[4] || leg == legs[5] || leg == legs[6]) {
                assertEquals(WalkResult.Status.FOUND, got.status(), "door, ladder, drop, gate, pond: " + name + " " + got);
            }
            if (leg == legs[2]) {
                boolean climbs = false;
                for (int i = 0; i < got.path().get().size(); i++) {
                    climbs |= got.path().get().isLadder(i);
                }
                assertTrue(climbs, "up the cliff by the ladder");
            }
            if (want.path().isPresent()) {
                assertEquals(want.path().get().cost(), got.path().get().cost(), 1e-9, name);
                assertEquals(want.path().get().size(), got.path().get().size(), name);
            }
        }
    }

    @Test
    void doorsLaddersAndWaterAreFlaggedAndNeverFloors() {
        FakeWorld world = village();
        WalkChunk chunk = extractor(world).extract(world.chunk(0, 0), 0, 0, 4, 4, s -> false, 0L);

        assertTrue((chunk.flags(6, 65, 4) & WalkChunk.DOOR) != 0, "oak door, lower half");
        assertTrue((chunk.flags(6, 66, 4) & WalkChunk.DOOR) != 0, "oak door, upper half");
        assertTrue((chunk.flags(10, 65, 2) & WalkChunk.DOOR) != 0, "fence gate");
        assertEquals(WalkChunk.SOLID, chunk.flags(5, 64, 1), "plain grass has no walk flags");
        WalkChunk pond = extractor(world).extract(world.chunk(0, 1), 0, 1, 4, 4, s -> false, 0L);
        assertTrue((pond.flags(1, 64, 19) & WalkChunk.WATER) != 0);
        assertTrue((pond.flags(1, 64, 19) & WalkChunk.PASSABLE) != 0, "water is passable");
        assertEquals(Set.of(BlockKey.pack(6, 65, 4), BlockKey.pack(6, 66, 4), BlockKey.pack(10, 65, 2)),
            set(chunk.doorBlocks()));
        // a fence top is recorded as a floor material — the search's isWalkFloor rejects it
        assertEquals("OAK_FENCE", chunk.floorMaterial(11, 65, 0));
        assertFalse(PassabilityRules.isWalkFloor(chunk.floorMaterial(11, 65, 0)));

        WalkChunk cliff = extractor(world).extract(world.chunk(1, 0), 1, 0, 4, 4, s -> false, 0L);
        assertTrue((cliff.flags(19, 66, 5) & WalkChunk.CLIMBABLE) != 0);
        assertNull(cliff.floorMaterial(19, 67, 5), "with the curated table a ladder is passable, so it is no floor");
    }

    @Test
    void outsideTheCaptureNothingIsPassableOrSolidAndFloorMaterialThrows() {
        FakeWorld world = village();
        CapturedWalkTerrain captured = capture(world, 0, 0, 4, 4); // one chunk, y 64..79

        assertFalse(captured.isSolid(5, 63, 5), "below the band");
        assertFalse(captured.isPassable(5, 63, 5));
        assertFalse(captured.isSolid(20, 64, 5), "another chunk");
        assertTrue(captured.outsideQueries() >= 3);
        assertThrows(IllegalStateException.class, () -> captured.floorMaterial(20, 64, 5));
        assertThrows(IllegalStateException.class, () -> captured.floorMaterial(5, 70, 5), "air is never a floor");
        assertEquals("GRASS_BLOCK", captured.floorMaterial(5, 64, 1));
    }

    @Test
    void theTopFloorOfABandLooksAboveTheBand() {
        FakeWorld world = new FakeWorld();
        world.groundY = 79; // the floor is the band's last block
        WalkChunk chunk = extractor(world).extract(world.chunk(0, 0), 0, 0, 4, 4, s -> false, 0L);
        assertEquals("GRASS_BLOCK", chunk.floorMaterial(3, 79, 3));
        assertNull(chunk.floorMaterial(3, 78, 3), "stone under grass is no floor");
    }

    @Test
    void uniformSectionsAreStoredAsOneByteAndEmptySectionsAreNotRead() {
        FakeWorld world = new FakeWorld();
        int[] reads = {0};
        SpanExtractor.BlockSource counting = (lx, y, lz) -> {
            reads[0]++;
            return world.at(lx, y, lz);
        };
        WalkChunk chunk = extractor(world).extract(counting, 0, 0, 2, 6, s -> s >= 5, 0L);

        assertEquals(1, chunk.mixedSections(), "only the grass section (y 64..79) is mixed");
        assertEquals(WalkChunk.SOLID, chunk.flags(3, 40, 3), "all-stone section");
        assertEquals(WalkChunk.PASSABLE, chunk.flags(3, 90, 3), "an empty section is air");
        assertEquals(3 * 4096 + 256, reads[0], "sections 2..4 read, 5..6 skipped; plus one floor-material read per column");
        assertTrue(chunk.covers(2, 6));
        assertFalse(chunk.covers(1, 6));
        assertNotNull(chunk.toString());
    }

    @Test
    void theBandIsClampedToTheWorld() {
        FakeWorld world = new FakeWorld();
        WalkChunkExtractor extractor = extractor(world);
        WalkChunk chunk = extractor.extract(world.chunk(0, 0), 0, 0, -10, 99, s -> s > 5, 0L);
        assertEquals(MIN_Y >> 4, chunk.minSection());
        assertEquals((MAX_Y - 1) >> 4, chunk.maxSection());
        assertThrows(IllegalArgumentException.class, () -> extractor.extract(world.chunk(0, 0), 0, 0, 30, 40, s -> false, 0L));
    }

    private static Set<Long> set(long[] keys) {
        Set<Long> out = new java.util.HashSet<>();
        for (long k : keys) {
            out.add(k);
        }
        return out;
    }
}
