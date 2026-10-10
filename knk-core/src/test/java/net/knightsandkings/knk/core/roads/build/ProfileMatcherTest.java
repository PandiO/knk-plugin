package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.domain.roads.RoadMaterialRole;
import net.knightsandkings.knk.core.roads.build.ProfileSet.Profile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileMatcherTest {

    private static int[] chainAlongRow(RoadMask mask, int y, int z, int fromX, int toX) {
        int[] chain = new int[toX - fromX + 1];
        for (int x = fromX; x <= toX; x++) {
            chain[x - fromX] = mask.indexOf(x, y, z);
            assertTrue(chain[x - fromX] >= 0, "span at " + x);
        }
        return chain;
    }

    @Test
    void histogramCoversTheCrossSectionAroundTheChainOnce() {
        GridFixture f = new GridFixture().layer(64, "aaaaaaaa", "SSSSSSSS", "SSSSSSSS", "SSSSSSSS", "aaaaaaaa");
        RoadMask m = ThinningTest.mask(f, 7, 4, 64);
        int[] dt = DistanceTransform.compute(m);
        int[] chain = chainAlongRow(m, 64, 2, 2, 5);

        Map<String, Integer> h = new ProfileMatcher(GridFixture.profiles()).histogram(m, dt, chain);
        // dt on the centre row is 3 → radius 2 → the whole 5-row cross-section for x 0..7 (Chebyshev).
        assertEquals(16, h.get(GridFixture.ANDESITE));
        assertEquals(24, h.get(GridFixture.STONE_BRICKS));
        assertEquals(2, h.size());
    }

    @Test
    void stoneBrickRoadWithKerbsMatchesTheTownRoad() {
        GridFixture f = new GridFixture().layer(64, "aaaaaaaa", "SSSSSSSS", "SSSSSSSS", "SSSSSSSS", "aaaaaaaa");
        RoadMask m = ThinningTest.mask(f, 7, 4, 64);
        int[] dt = DistanceTransform.compute(m);

        assertEquals(OptionalInt.of(1), new ProfileMatcher(GridFixture.profiles()).match(m, dt, chainAlongRow(m, 64, 2, 1, 6)));
    }

    @Test
    void gravelPathMatchesTheGravelProfile() {
        GridFixture f = new GridFixture().layer(64, "GGGGGGGG");
        RoadMask m = ThinningTest.mask(f, 7, 0, 64);
        int[] dt = DistanceTransform.compute(m);

        assertEquals(OptionalInt.of(2), new ProfileMatcher(GridFixture.profiles()).match(m, dt, chainAlongRow(m, 64, 0, 0, 7)));
    }

    @Test
    void mixedChainTakesTheDominantMaterialsProfile() {
        GridFixture f = new GridFixture().layer(64, "GGGSSSSSSS");
        RoadMask m = ThinningTest.mask(f, 9, 0, 64);
        int[] dt = DistanceTransform.compute(m);
        ProfileMatcher matcher = new ProfileMatcher(GridFixture.profiles());

        assertEquals(OptionalInt.of(1), matcher.match(m, dt, chainAlongRow(m, 64, 0, 0, 9)), "7 stone bricks vs 3 gravel");
        assertEquals(OptionalInt.of(2), matcher.match(m, dt, chainAlongRow(m, 64, 0, 0, 2)), "the gravel part alone");
    }

    @Test
    void noSharedMaterialMeansNoProfile() {
        ProfileMatcher matcher = new ProfileMatcher(GridFixture.profiles());
        assertEquals(OptionalInt.empty(), matcher.match(Map.of("DIRT_PATH", 10), GridFixture.profiles().profiles()));
        assertEquals(OptionalInt.empty(), matcher.match(Map.of(), GridFixture.profiles().profiles()));
        assertEquals(OptionalInt.empty(), matcher.match(RoadMask.empty(), new int[0], new int[0]));
    }

    @Test
    void tiesGoToTheLowerId() {
        Profile a = new Profile(7, "A", true, 1, 5, Set.of(), List.of(GridFixture.mat("GRAVEL", RoadMaterialRole.SURFACE, false, 1.0)));
        Profile b = new Profile(3, "B", true, 1, 5, Set.of(), List.of(GridFixture.mat("GRAVEL", RoadMaterialRole.SURFACE, false, 0.5)));
        ProfileMatcher matcher = new ProfileMatcher(new ProfileSet(List.of(a, b)));
        assertEquals(OptionalInt.of(3), matcher.match(Map.of("GRAVEL", 10), List.of(a, b)));
        assertEquals(OptionalInt.of(3), matcher.match(Map.of("GRAVEL", 10), List.of(b, a)));
    }

    @Test
    void profilesWithoutSharesUseUniformWeights() {
        Profile fresh = new Profile(9, "Fresh", true, 1, 5, Set.of(), List.of(
            GridFixture.mat("GRAVEL", RoadMaterialRole.SURFACE, false, 0.0),
            GridFixture.mat("COARSE_DIRT", RoadMaterialRole.ACCENT, false, 0.0),
            GridFixture.mat("SNOW", RoadMaterialRole.OVERLAY, false, 0.0)));
        double similarity = ProfileMatcher.cosine(Map.of("GRAVEL", 5, "COARSE_DIRT", 5), 10, fresh);
        assertEquals(1.0, similarity, 1e-9, "overlays are ignored; the two floors match perfectly");
    }

    @Test
    void scopedProfilesCompeteOnlyInsideTheirTown() {
        Profile kardenna = new Profile(5, "Kardenna", true, 1, 5, Set.of(42), List.of(
            GridFixture.mat(GridFixture.STONE_BRICKS, RoadMaterialRole.SURFACE, false, 1.0)));
        ScopeLookup inTown = (x, z) -> java.util.OptionalInt.of(42);
        ScopeLookup wilderness = ScopeLookup.NONE;
        GridFixture f = new GridFixture().layer(64, "SSSSSSSS");
        int[] chain;
        RoadMask m;
        {
            SpanGrid grid = f.spanGrid(new ProfileSet(List.of(GridFixture.townRoad(), kardenna), inTown));
            m = RoadMask.of(grid, RoadMaskTest.allSpans(grid, 7, 0, 64));
            chain = chainAlongRow(m, 64, 0, 0, 7);
            int[] dt = DistanceTransform.compute(m);
            // Pure stone bricks: the Kardenna profile (share 1.0 on one material) matches perfectly, the
            // town road (bricks + kerbs + accents) slightly less.
            assertEquals(OptionalInt.of(5), new ProfileMatcher(new ProfileSet(List.of(GridFixture.townRoad(), kardenna), inTown)).match(m, dt, chain));
            assertEquals(OptionalInt.of(1), new ProfileMatcher(new ProfileSet(List.of(GridFixture.townRoad(), kardenna), wilderness)).match(m, dt, chain));
        }
    }
}
