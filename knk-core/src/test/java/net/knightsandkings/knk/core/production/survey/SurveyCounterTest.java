package net.knightsandkings.knk.core.production.survey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.production.survey.SurveyCounter.EntitySighting;
import net.knightsandkings.knk.core.production.survey.SurveyCounter.SharedArea;
import net.knightsandkings.knk.core.production.survey.SurveyProfile.UnitType;

/** Survey counting (PandiO/knk-workspace#679, PRODUCTION_KINDS § 3.2, § 8): tree detection fixtures, blocks, crops,
 * flowers, water, entities, the shared split (G7), placeholders and idempotence. */
class SurveyCounterTest {

    private static final AreaShape BOX = new AreaShape.Cuboid(0, 60, 0, 31, 90, 31);
    private static final int OWN = 7;

    private static SurveyCounter.Result count(FakeBlocks blocks, Set<UnitType> types) {
        return count(blocks, SurveyProfile.of(types), BOX, List.of(), List.of(), PlaceholderOverlay.NONE);
    }

    private static SurveyCounter.Result count(FakeBlocks blocks, SurveyProfile profile, AreaShape shape,
                                              List<EntitySighting> entities, List<SharedArea> shared, PlaceholderOverlay overlay) {
        return SurveyCounter.count(new SurveyCounter.Input("world", OWN, shape, profile, blocks, overlay, entities, shared));
    }

    private static Map<String, Long> totals(SurveyCounter.Result result) {
        return result.units().stream().collect(Collectors.groupingBy(SurveyUnit::unitKey, Collectors.summingLong(SurveyUnit::count)));
    }

    /** An oak-like tree: a trunk of {@code height} logs at (x, 64, z) with a leaves cap. */
    private static void tree(FakeBlocks blocks, int x, int z, int height, String log, String leaves) {
        blocks.fill(x, 64, z, x, 63 + height, z, log);
        blocks.fill(x - 1, 64 + height, z - 1, x + 1, 65 + height, z + 1, leaves);
    }

    @Test
    void treesAreLogClustersWithLeavesBySpeciesAndSize() {
        FakeBlocks blocks = new FakeBlocks();
        tree(blocks, 3, 3, 5, "OAK_LOG", "OAK_LEAVES");
        tree(blocks, 10, 10, 12, "BIRCH_LOG", "BIRCH_LEAVES");
        // a big spruce: a 2×2 trunk of 13 high = 52 logs
        blocks.fill(20, 64, 20, 21, 76, 21, "SPRUCE_LOG").fill(19, 77, 19, 22, 78, 22, "SPRUCE_LEAVES");
        // a fence post of logs without leaves: not a tree
        blocks.fill(28, 64, 2, 28, 66, 2, "OAK_LOG");

        SurveyCounter.Result result = count(blocks, EnumSet.of(UnitType.TREE));

        assertEquals(Map.of("TREE:OAK:SMALL", 1L, "TREE:BIRCH:MEDIUM", 1L, "TREE:SPRUCE:LARGE", 1L), totals(result));
        assertEquals(3, result.trees());
    }

    @Test
    void mixedTreesGoToTheMajoritySpeciesAndDiagonalBranchesJoin() {
        FakeBlocks blocks = new FakeBlocks();
        tree(blocks, 5, 5, 4, "JUNGLE_LOG", "JUNGLE_LEAVES");
        blocks.set(6, 66, 6, "OAK_LOG").set(7, 67, 7, "OAK_LOG"); // a diagonal branch of another species
        SurveyCounter.Result result = count(blocks, EnumSet.of(UnitType.TREE));
        assertEquals(Map.of("TREE:JUNGLE:SMALL", 1L), totals(result));
    }

    @Test
    void aLogClusterOverMaxLogsIsABuilding() {
        FakeBlocks blocks = new FakeBlocks();
        blocks.fill(0, 64, 0, 9, 68, 9, "OAK_LOG").fill(0, 69, 0, 9, 69, 9, "OAK_LEAVES"); // 500 logs
        SurveyCounter.Result result = count(blocks, EnumSet.of(UnitType.TREE));
        assertTrue(result.units().isEmpty());
        assertEquals(1, result.buildings());
    }

    @Test
    void strippedLogsAndSaplings() {
        FakeBlocks blocks = new FakeBlocks();
        blocks.fill(1, 64, 1, 1, 66, 1, "STRIPPED_OAK_LOG").set(1, 67, 1, "OAK_LEAVES");
        blocks.set(4, 64, 4, "OAK_SAPLING").set(5, 64, 4, "MANGROVE_PROPAGULE").set(6, 64, 4, "POTTED_OAK_SAPLING");
        SurveyCounter.Result result = count(blocks, EnumSet.of(UnitType.TREE, UnitType.SAPLING));
        assertEquals(Map.of("SAPLING:OAK", 1L, "SAPLING:MANGROVE", 1L), totals(result));
    }

    @Test
    void rockAndOresInTheWholeVolume() {
        FakeBlocks blocks = new FakeBlocks();
        blocks.fill(0, 60, 0, 3, 61, 3, "STONE").set(1, 60, 1, "COAL_ORE").set(2, 60, 2, "DEEPSLATE_IRON_ORE")
                .set(0, 62, 0, "GRASS_BLOCK").set(40, 60, 40, "STONE");
        SurveyCounter.Result result = count(blocks, EnumSet.of(UnitType.BLOCK));
        assertEquals(Map.of("BLOCK:STONE", 30L, "BLOCK:COAL_ORE", 1L, "BLOCK:DEEPSLATE_IRON_ORE", 1L), totals(result));
    }

    @Test
    void theProfilesMaterialsReplaceTheDefaultList() {
        FakeBlocks blocks = new FakeBlocks();
        blocks.fill(0, 60, 0, 1, 60, 1, "STONE").set(0, 61, 0, "COAL_ORE").set(1, 61, 1, "GRASS_BLOCK");
        SurveyProfile profile = SurveyProfile.parse("{\"unitTypes\":[\"BLOCK\"],\"materials\":[\"coal_ore\",\"GRASS_BLOCK\"]}", 2);
        SurveyCounter.Result result = count(blocks, profile, BOX, List.of(), List.of(), PlaceholderOverlay.NONE);
        assertEquals(Map.of("BLOCK:COAL_ORE", 1L, "BLOCK:GRASS_BLOCK", 1L), totals(result));
    }

    @Test
    void cropsPerTypeAndFlowersOncePerTallFlower() {
        FakeBlocks blocks = new FakeBlocks();
        blocks.fill(0, 64, 0, 2, 64, 2, "WHEAT").set(5, 64, 5, "ATTACHED_MELON_STEM").set(6, 64, 5, "MELON_STEM")
                .set(7, 64, 5, "MELON").set(8, 64, 5, "SUGAR_CANE").set(8, 65, 5, "SUGAR_CANE");
        blocks.set(10, 64, 10, "POPPY").set(11, 64, 10, "ROSE_BUSH").set(11, 65, 10, "ROSE_BUSH");
        SurveyCounter.Result crops = count(blocks, EnumSet.of(UnitType.CROP));
        assertEquals(Map.of("CROP:WHEAT", 9L, "CROP:MELON_STEM", 2L, "CROP:MELON", 1L, "CROP:SUGAR_CANE", 2L), totals(crops));
        SurveyCounter.Result flowers = count(blocks, EnumSet.of(UnitType.FLOWER));
        assertEquals(Map.of("FLOWER:POPPY", 1L, "FLOWER:ROSE_BUSH", 1L), totals(flowers));
    }

    @Test
    void waterSourcesByBiomeWithDepthAndOpenSky() {
        FakeBlocks blocks = new FakeBlocks();
        blocks.fill(0, 61, 0, 3, 63, 3, "WATER"); // 16 columns × 3 deep, open sky
        blocks.set(0, 70, 0, "OAK_LEAVES"); // one column under a canopy
        blocks.fill(10, 63, 10, 10, 63, 11, "WATER");
        blocks.biome(10, 63, 10, "RIVER").biome(10, 63, 11, "RIVER");
        blocks.flowing.add(PlaceholderOverlay.key(10, 63, 11)); // flowing: not counted
        SurveyCounter.Result result = count(blocks, EnumSet.of(UnitType.WATER));
        assertEquals(Map.of("WATER:PLAINS", 48L, "WATER:RIVER", 1L), totals(result));
        SurveyUnit plains = result.units().stream().filter(u -> u.unitKey().equals("WATER:PLAINS")).findFirst().orElseThrow();
        assertEquals("{\"columns\":16,\"avgDepth\":3.00,\"openSkyShare\":0.94}", plains.extra());
    }

    @Test
    void entitiesInsideTheAreaOfTheCountedTypes() {
        FakeBlocks blocks = new FakeBlocks();
        List<EntitySighting> seen = List.of(new EntitySighting("SHEEP", 1, 64, 1), new EntitySighting("sheep", 2, 64, 2),
                new EntitySighting("COW", 3, 64, 3), new EntitySighting("ZOMBIE", 4, 64, 4),
                new EntitySighting("SHEEP", 50, 64, 50));
        SurveyCounter.Result result = count(blocks, SurveyProfile.of(EnumSet.of(UnitType.ENTITY)), BOX, seen, List.of(),
                PlaceholderOverlay.NONE);
        assertEquals(Map.of("ENTITY:SHEEP", 2L, "ENTITY:COW", 1L), totals(result));
    }

    @Test
    void sharedUnitsAreReportedPerSharedByCountingDistinctOtherStructures() {
        FakeBlocks blocks = new FakeBlocks();
        tree(blocks, 2, 2, 4, "OAK_LOG", "OAK_LEAVES");
        tree(blocks, 12, 2, 4, "OAK_LOG", "OAK_LEAVES");
        tree(blocks, 22, 2, 4, "OAK_LOG", "OAK_LEAVES");
        List<SharedArea> shared = List.of(
                new SharedArea(101, 20, new AreaShape.Radius(12, 2, 3, -64, 319)), // structure 20 reaches the middle tree
                new SharedArea(102, 20, new AreaShape.Cuboid(10, 0, 0, 30, 100, 5)), // structure 20 again: counted once
                new SharedArea(103, 30, new AreaShape.Cuboid(20, 0, 0, 30, 100, 5)), // structure 30: the right tree
                new SharedArea(104, OWN, new AreaShape.Cuboid(0, 0, 0, 30, 100, 5))); // own structure: never counted
        SurveyCounter.Result result = count(blocks, SurveyProfile.of(EnumSet.of(UnitType.TREE)), BOX, List.of(), shared,
                PlaceholderOverlay.NONE);
        assertEquals(List.of(new SurveyUnit("TREE:OAK:SMALL", 1, 1, null), new SurveyUnit("TREE:OAK:SMALL", 1, 2, null),
                new SurveyUnit("TREE:OAK:SMALL", 1, 3, null)), result.units());
    }

    @Test
    void anOverlapSharesOnlyTheUnitTypesItsStructureCounts_875() {
        FakeBlocks blocks = new FakeBlocks();
        blocks.fill(2, 64, 2, 4, 64, 2, "WHEAT"); // three wheat inside a logger's radius
        tree(blocks, 12, 2, 4, "OAK_LOG", "OAK_LEAVES");
        AreaShape loggerRadius = new AreaShape.Cuboid(0, 0, 0, 30, 100, 5);
        SurveyProfile farmAndTrees = SurveyProfile.of(EnumSet.of(UnitType.CROP, UnitType.TREE));

        // the logger counts trees only: the crops are the farm's alone, the tree is split
        SurveyCounter.Result typed = count(blocks, farmAndTrees, BOX, List.of(),
                List.of(new SharedArea(201, 40, loggerRadius, EnumSet.of(UnitType.TREE))), PlaceholderOverlay.NONE);
        assertEquals(List.of(new SurveyUnit("CROP:WHEAT", 3, 1, null), new SurveyUnit("TREE:OAK:SMALL", 1, 2, null)),
                typed.units());

        // without unit types (an API before #875) the overlap shares every unit, as before
        SurveyCounter.Result untyped = count(blocks, farmAndTrees, BOX, List.of(),
                List.of(new SharedArea(201, 40, loggerRadius)), PlaceholderOverlay.NONE);
        assertEquals(List.of(new SurveyUnit("CROP:WHEAT", 3, 2, null), new SurveyUnit("TREE:OAK:SMALL", 1, 2, null)),
                untyped.units());

        // an overlap that counts nothing (empty set) shares nothing
        SurveyCounter.Result none = count(blocks, farmAndTrees, BOX, List.of(),
                List.of(new SharedArea(201, 40, loggerRadius, EnumSet.noneOf(UnitType.class))), PlaceholderOverlay.NONE);
        assertEquals(List.of(new SurveyUnit("CROP:WHEAT", 3, 1, null), new SurveyUnit("TREE:OAK:SMALL", 1, 1, null)),
                none.units());
    }

    @Test
    void placeholdersCountAsTheirOriginal() {
        FakeBlocks blocks = new FakeBlocks();
        blocks.fill(0, 60, 0, 1, 60, 0, "STONE"); // (0,60,0) is depleted stone waiting to become coal ore
        blocks.set(5, 64, 5, "OAK_SAPLING"); // a felled tree's sapling
        PlaceholderOverlay overlay = PlaceholderOverlay.of("world", Map.of(
                PlaceholderOverlay.key(0, 60, 0), "COAL_ORE",
                PlaceholderOverlay.key(5, 64, 5), "OAK_LOG", PlaceholderOverlay.key(5, 65, 5), "OAK_LOG",
                PlaceholderOverlay.key(5, 66, 5), "OAK_LEAVES"));
        SurveyCounter.Result result = count(blocks, SurveyProfile.of(EnumSet.of(UnitType.BLOCK, UnitType.TREE, UnitType.SAPLING)),
                BOX, List.of(), List.of(), overlay);
        assertEquals(Map.of("BLOCK:STONE", 1L, "BLOCK:COAL_ORE", 1L, "TREE:OAK:SMALL", 1L), totals(result));
        assertEquals(4, result.placeholders());
    }

    @Test
    void theSameWorldGivesTheSameHash() {
        FakeBlocks a = new FakeBlocks();
        tree(a, 3, 3, 5, "OAK_LOG", "OAK_LEAVES");
        a.fill(0, 60, 0, 5, 60, 5, "STONE");
        FakeBlocks b = new FakeBlocks();
        b.fill(0, 60, 0, 5, 60, 5, "STONE");
        tree(b, 3, 3, 5, "OAK_LOG", "OAK_LEAVES");
        Set<UnitType> all = EnumSet.allOf(UnitType.class);
        String first = count(a, all).contentHash();
        assertEquals(first, count(b, all).contentHash());
        b.set(20, 60, 20, "STONE");
        assertNotEquals(first, count(b, all).contentHash());
        assertEquals(64, first.length());
    }

    @Test
    void columnsOfUncapturedChunksAreNotCounted() {
        FakeBlocks blocks = new FakeBlocks().fill(0, 60, 0, 31, 60, 31, "STONE").missingChunk(1, 1);
        SurveyCounter.Result result = count(blocks, EnumSet.of(UnitType.BLOCK));
        assertEquals(768L, totals(result).get("BLOCK:STONE"));
        assertEquals(256, result.missingColumns());
        assertTrue(result.note().contains("columns not loaded 256"));
    }

    @Test
    void onlyColumnsInsideAPolygonCount() {
        FakeBlocks blocks = new FakeBlocks().fill(0, 60, 0, 10, 60, 10, "STONE");
        AreaShape triangle = AreaShape.Polygon.of(List.of(new int[] {0, 0}, new int[] {10, 0}, new int[] {0, 10}), 60, 60);
        SurveyCounter.Result result = count(blocks, SurveyProfile.of(EnumSet.of(UnitType.BLOCK)), triangle, List.of(),
                List.of(), PlaceholderOverlay.NONE);
        assertEquals(66L, totals(result).get("BLOCK:STONE"));
    }

    @Test
    void anEmptyProfileCountsNothing() {
        FakeBlocks blocks = new FakeBlocks().fill(0, 60, 0, 3, 60, 3, "STONE");
        SurveyCounter.Result result = count(blocks, Set.of());
        assertTrue(result.units().isEmpty());
        assertEquals(0, result.blocksRead());
        assertNull(result.units().stream().findFirst().orElse(null));
    }
}
