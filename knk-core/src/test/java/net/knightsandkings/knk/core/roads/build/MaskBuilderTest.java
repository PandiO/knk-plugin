package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.roads.build.MaskBuilder.Region;
import net.knightsandkings.knk.core.roads.build.MaskBuilder.Result;
import net.knightsandkings.knk.core.roads.build.MaskBuilder.Seed;
import net.knightsandkings.knk.core.util.BlockKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaskBuilderTest {
    private static final Region WIDE = new Region(-100, -100, 100, 100);
    private static final BuildParameters PARAMS = BuildParameters.defaults();

    private static Result build(GridFixture f, Region region, Seed... seeds) {
        return new MaskBuilder(f.spanGrid(), PARAMS).build(List.of(seeds), region);
    }

    private static Result build(GridFixture f, BuildParameters params, Region region, Seed... seeds) {
        return new MaskBuilder(f.spanGrid(), params).build(List.of(seeds), region);
    }

    @Test
    void reachesEverythingConnectedToASeedAndNothingElse() {
        GridFixture f = new GridFixture().layer(64, "SSSS..SSS");
        Result r = build(f, WIDE, new Seed(0, 64, 0));

        assertEquals(4, r.mask().size());
        assertTrue(r.mask().indexOf(3, 64, 0) >= 0);
        assertEquals(RoadMask.NONE, r.mask().indexOf(6, 64, 0), "the gap of two air blocks separates the pieces");
        assertTrue(r.warnings().isEmpty());
        assertFalse(r.cappedAtCellLimit());
        assertEquals(List.of(r.mask().indexOf(0, 64, 0)), r.seedSpans());
    }

    @Test
    void severalSeedsUnionTheirComponents() {
        GridFixture f = new GridFixture().layer(64, "SSSS..SSS");
        Result r = build(f, WIDE, new Seed(0, 64, 0), new Seed(7, 64, 0), new Seed(1, 64, 0));

        assertEquals(7, r.mask().size());
        assertEquals(3, r.seedSpans().size());
    }

    @Test
    void regionClipsTheMask() {
        GridFixture f = new GridFixture().layer(64, "SSSSSSSSSS");
        Result r = build(f, new Region(0, 0, 4, 0), new Seed(2, 64, 0));

        assertEquals(5, r.mask().size());
        assertEquals(RoadMask.NONE, r.mask().indexOf(5, 64, 0));
        assertTrue(r.mask().isBorder(r.mask().indexOf(4, 64, 0)));
    }

    @Test
    void cellCapStopsTheBfsWithAWarningAtTheLastSpan() {
        GridFixture f = new GridFixture().layer(64, "SSSSSSSSSS");
        Result r = build(f, PARAMS.withMaxCells(4), WIDE, new Seed(0, 64, 0));

        assertEquals(4, r.mask().size());
        assertTrue(r.cappedAtCellLimit());
        assertEquals(1, r.warnings().size());
        BuildWarning w = r.warnings().get(0);
        assertEquals(MaskBuilder.WARN_CELL_CAP, w.message());
        assertEquals(4, w.x(), "the span that did not fit");
        assertEquals(64, w.y());
        assertEquals(MaskBuilder.WARN_CELL_CAP + " at (4, 64, 0)", w.text());
    }

    @Test
    void seedsSnapToTheirColumnFeetOrFloor() {
        GridFixture f = new GridFixture().layer(64, "SSS");
        MaskBuilder b = new MaskBuilder(f.spanGrid(), PARAMS);

        assertEquals(OptionalLong.of(BlockKey.pack(1, 64, 0)), b.snapSeed(new Seed(1, 64, 0), WIDE), "floor y");
        assertEquals(OptionalLong.of(BlockKey.pack(1, 64, 0)), b.snapSeed(new Seed(1, 65, 0), WIDE), "feet y");
        assertEquals(OptionalLong.of(BlockKey.pack(1, 64, 0)), b.snapSeed(new Seed(1, 67, 0), WIDE), "floating above: SiegeFloor drops onto the floor");
        assertEquals(OptionalLong.of(BlockKey.pack(1, 64, 0)), b.snapSeed(new Seed(1, 63, 0), WIDE), "inside the floor block: lifted");
    }

    @Test
    void seedsSnapToTheNearestSpanWithinTheRadius() {
        GridFixture f = new GridFixture().layer(64, "S........S");
        MaskBuilder b = new MaskBuilder(f.spanGrid(), PARAMS);

        assertEquals(OptionalLong.of(BlockKey.pack(0, 64, 0)), b.snapSeed(new Seed(3, 65, 0), WIDE), "3 away beats 6 away");
        assertEquals(OptionalLong.of(BlockKey.pack(9, 64, 0)), b.snapSeed(new Seed(6, 65, 0), WIDE));
        assertEquals(OptionalLong.of(BlockKey.pack(0, 64, 0)), b.snapSeed(new Seed(0, 65, 8), WIDE), "8 = the radius");
        assertTrue(b.snapSeed(new Seed(0, 65, 9), WIDE).isEmpty(), "9 > the radius");
        assertTrue(b.snapSeed(new Seed(0, 80, 0), WIDE).isEmpty(), "too high above");
        assertEquals(OptionalLong.of(BlockKey.pack(0, 64, 0)), b.snapSeed(new Seed(2, 68, 0), WIDE), "4 up is still snapped");
    }

    @Test
    void seedsOnlySnapInsideTheRegion() {
        GridFixture f = new GridFixture().layer(64, "S........S");
        MaskBuilder b = new MaskBuilder(f.spanGrid(), PARAMS);

        assertEquals(OptionalLong.of(BlockKey.pack(9, 64, 0)), b.snapSeed(new Seed(3, 65, 0), new Region(5, -5, 20, 5)));
    }

    @Test
    void unmatchedSeedIsAWarningNotAnError() {
        GridFixture f = new GridFixture().layer(64, "SSS");
        Result r = build(f, WIDE, new Seed(50, 64, 50), new Seed(1, 64, 0));

        assertEquals(3, r.mask().size());
        assertEquals(1, r.warnings().size());
        assertEquals(MaskBuilder.WARN_SEED_UNMATCHED, r.warnings().get(0).message());
        assertEquals(50, r.warnings().get(0).x());
    }

    @Test
    void noSeedsGiveAnEmptyMask() {
        GridFixture f = new GridFixture().layer(64, "SSS");
        Result r = build(f, WIDE);
        assertEquals(0, r.mask().size());
        assertTrue(r.seedSpans().isEmpty());
    }

    @Test
    void ambiguousKerbStaysAmbiguousCourtyardBeyondReachGoes() {
        // A 3-wide stone-brick road (z 0..2) with a cobblestone kerb (z 3) and a cobblestone courtyard
        // (z 4..9) behind it. Reach 3: kerb (1 from the road) and the first two courtyard rows stay.
        GridFixture f = new GridFixture();
        for (int z = 0; z < 3; z++) f.layer(0, 64, z, "SSSSSSSSSS");
        for (int z = 3; z < 10; z++) f.layer(0, 64, z, "cccccccccc");
        Result r = build(f, WIDE, new Seed(5, 65, 1));

        RoadMask m = r.mask();
        assertTrue(m.indexOf(5, 64, 3) >= 0, "kerb, distance 1");
        assertTrue(m.indexOf(5, 64, 4) >= 0, "distance 2");
        assertTrue(m.indexOf(5, 64, 5) >= 0, "distance 3 = reach");
        assertEquals(RoadMask.NONE, m.indexOf(5, 64, 6), "distance 4 > reach");
        assertEquals(RoadMask.NONE, m.indexOf(5, 64, 9));
        assertEquals(60, m.size(), "3 road rows + 3 ambiguous rows × 10");
        assertEquals(100, r.reachedBeforeFilter());
    }

    @Test
    void ambiguousReachZeroKeepsOnlyUnambiguousSpans() {
        GridFixture f = new GridFixture().layer(64, "SSSccc");
        Result r = build(f, PARAMS.withAmbiguousReach(0), WIDE, new Seed(0, 64, 0));
        assertEquals(3, r.mask().size());
    }

    @Test
    void whatIsOnlyReachableThroughATooLongAmbiguousStretchIsDropped() {
        // Stone bricks, then 7 cobblestone (more than twice the reach), then stone bricks again: the
        // middle cobblestone is 4 from either road, so the far road is cut off.
        GridFixture f = new GridFixture().layer(64, "SSScccccccSSS");
        Result r = build(f, WIDE, new Seed(0, 64, 0));

        RoadMask m = r.mask();
        assertEquals(6, m.size(), "3 stone bricks + 3 cobblestones within reach");
        assertEquals(RoadMask.NONE, m.indexOf(6, 64, 0), "4 from both roads");
        assertEquals(RoadMask.NONE, m.indexOf(10, 64, 0), "the far stone bricks are unreachable now");
        assertEquals(RoadMask.NONE, m.indexOf(7, 64, 0), "cobblestone within reach of the far road, but disconnected");
    }

    @Test
    void aShortAmbiguousBridgeStillJoinsTwoRoads() {
        GridFixture f = new GridFixture().layer(64, "SSScccSSS");
        Result r = build(f, WIDE, new Seed(0, 64, 0));
        assertEquals(9, r.mask().size());
        assertTrue(r.mask().indexOf(8, 64, 0) >= 0);
    }

    @Test
    void anAllAmbiguousWorldGivesNothingUnlessAProfileMakesItUnambiguous() {
        GridFixture f = new GridFixture().layer(64, "ccccc");
        Result onlyTownRoad = build(f, WIDE, new Seed(0, 64, 0));
        assertEquals(0, onlyTownRoad.mask().size());

        ProfileSet cobbleRoad = new ProfileSet(List.of(GridFixture.townRoad(),
            new ProfileSet.Profile(3, "Cobble road", true, 1, 5, java.util.Set.of(),
                List.of(GridFixture.mat(GridFixture.COBBLESTONE, net.knightsandkings.knk.core.domain.roads.RoadMaterialRole.SURFACE, false, 1.0)))));
        Result withCobbleRoad = new MaskBuilder(f.spanGrid(cobbleRoad), PARAMS).build(List.of(new Seed(0, 64, 0)), WIDE);
        assertEquals(5, withCobbleRoad.mask().size());
    }

    @Test
    void seedOnAnAmbiguousCellBeyondReachIsDroppedWithItsSpan() {
        GridFixture f = new GridFixture().layer(64, "cccccccc");
        Result r = build(f, WIDE, new Seed(3, 64, 0));
        assertEquals(0, r.mask().size());
        assertTrue(r.seedSpans().isEmpty(), "the seed's span did not survive the filter");
        assertTrue(r.warnings().isEmpty(), "it was matched, just filtered");
    }

    @Test
    void followsStepsAndCarriesLevelsAndGates() {
        GridFixture f = new GridFixture()
            .layer(64, "SS..", "....")
            .layer(65, "../S", "....")
            .layer(72, "SSSS", "....")   // a street 8 blocks above: separate
            .gate(4, 1, 64, 0, 2);
        Result r = build(f, WIDE, new Seed(0, 64, 0), new Seed(0, 72, 0));

        RoadMask m = r.mask();
        assertEquals(8, m.size());
        assertTrue(m.indexOf(3, 65, 0) >= 0, "up the stair");
        assertEquals(2, m.levelCount());
        assertEquals(4, m.gateDoor(m.indexOf(1, 64, 0)));
    }

    @Test
    void regionHelpers() {
        Region tile = Region.tile(2, -1, 16);
        assertEquals(32, tile.minX());
        assertEquals(47, tile.maxX());
        assertEquals(-16, tile.minZ());
        assertEquals(-1, tile.maxZ());
        assertTrue(tile.contains(32, -16));
        assertFalse(tile.contains(48, -16));
        Region grown = tile.grow(4);
        assertEquals(28, grown.minX());
        assertEquals(3, grown.maxZ());
        assertThrows(IllegalArgumentException.class, () -> new Region(0, 0, -1, 0));
    }
}
