package net.knightsandkings.knk.core.roads.survey;

import net.knightsandkings.knk.core.domain.roads.RoadMaterialRole;
import net.knightsandkings.knk.core.roads.survey.ProposedProfile.Material;
import net.knightsandkings.knk.core.roads.survey.SurveyStats.MaterialCounts;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.IntFunction;

import static net.knightsandkings.knk.core.roads.survey.CrossSections.COARSE_DIRT;
import static net.knightsandkings.knk.core.roads.survey.CrossSections.COBBLESTONE;
import static net.knightsandkings.knk.core.roads.survey.CrossSections.GRASS_BLOCK;
import static net.knightsandkings.knk.core.roads.survey.CrossSections.GRAVEL;
import static net.knightsandkings.knk.core.roads.survey.CrossSections.PODZOL;
import static net.knightsandkings.knk.core.roads.survey.CrossSections.POLISHED_ANDESITE;
import static net.knightsandkings.knk.core.roads.survey.CrossSections.SNOW;
import static net.knightsandkings.knk.core.roads.survey.CrossSections.STONE_BRICKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProfileLearner on synthetic cross-sections: the plan's four scenarios (a-d) plus overlays, noise,
 * accents, percentile widths and the road-likeness rule.
 */
class ProfileLearnerTest {
    private final ProfileLearner learner = new ProfileLearner();

    private static Material material(ProposedProfile profile, String name) {
        return profile.material(name).orElseThrow(() -> new AssertionError(name + " not proposed in " + profile));
    }

    @Test
    void scenarioA_stoneBrickRoadWithAndesiteKerbsOnGrass() {
        ProposedProfile profile = learner.learn(SurveyStats.of(CrossSections.stoneBrickRoadWithKerbs(100)));

        assertEquals(List.of(STONE_BRICKS, POLISHED_ANDESITE),
            profile.materials().stream().map(Material::material).toList(), "grass is terrain, not proposed");

        Material surface = material(profile, STONE_BRICKS);
        assertEquals(RoadMaterialRole.SURFACE, surface.role());
        assertEquals(260.0 / 300.0, surface.centreShare(), 1e-9);
        assertEquals(0.0, surface.edgeShare(), 1e-9);
        assertFalse(surface.ambiguous());
        assertEquals(100, surface.samples());

        Material kerb = material(profile, POLISHED_ANDESITE);
        assertEquals(RoadMaterialRole.EDGE, kerb.role());
        assertEquals(40.0 / 300.0, kerb.centreShare(), 1e-9);
        assertEquals(1.0, kerb.edgeShare(), 1e-9, "every run ends on a kerb");
        assertFalse(kerb.ambiguous());

        assertEquals(7, profile.widthMin(), "5 surface + 2 kerbs");
        assertEquals(7, profile.widthMax());
        assertEquals(100, profile.sampleCount());
    }

    @Test
    void scenarioB_oneWideGravelPathInAForest() {
        ProposedProfile profile = learner.learn(SurveyStats.of(CrossSections.gravelPathInForest(150)));

        assertEquals(1, profile.materials().size(), "only gravel: " + profile.materials());
        Material gravel = material(profile, GRAVEL);
        assertEquals(RoadMaterialRole.SURFACE, gravel.role(), "a 1-wide path's material is its surface, not an edge");
        assertEquals(1.0, gravel.centreShare(), 1e-9);
        assertEquals(1.0, gravel.edgeShare(), 1e-9);
        assertFalse(gravel.ambiguous());
        assertTrue(profile.material(PODZOL).isEmpty(), "a patch never contiguous with the path is not proposed");
        assertTrue(profile.material(GRASS_BLOCK).isEmpty());
        assertEquals(1, profile.widthMin());
        assertEquals(1, profile.widthMax());
    }

    @Test
    void scenarioC_cobblestoneKerbNextToCobblestoneCourtyardIsAmbiguous() {
        ProposedProfile profile = learner.learn(SurveyStats.of(CrossSections.cobbleKerbNextToCobbleCourtyard(180, 6)));

        Material kerb = material(profile, COBBLESTONE);
        assertEquals(RoadMaterialRole.EDGE, kerb.role());
        assertTrue(kerb.ambiguous(), "seen in the outer bucket outside the run in 4 of every 30 samples");
        Material surface = material(profile, STONE_BRICKS);
        assertEquals(RoadMaterialRole.SURFACE, surface.role());
        assertFalse(surface.ambiguous());
        assertTrue(profile.material(GRASS_BLOCK).isEmpty());
        assertEquals(7, profile.widthMin(), "the courtyard behind the grass strip never joins the run");
        assertEquals(7, profile.widthMax());
    }

    @Test
    void scenarioC_withoutTheCourtyardTheKerbIsNotAmbiguous() {
        ProposedProfile profile = learner.learn(SurveyStats.of(CrossSections.cobbleKerbNextToCobbleCourtyard(180, 1_000)));

        Material kerb = material(profile, COBBLESTONE);
        assertEquals(RoadMaterialRole.EDGE, kerb.role());
        assertFalse(kerb.ambiguous());
    }

    @Test
    void scenarioD_mergingTwoSurveysEqualsLearningOnTheConcatenation() {
        List<SurveySample> wide = CrossSections.stoneBrickRoadWithKerbs(100);
        List<SurveySample> narrow = CrossSections.walk(
            CrossSections.road(STONE_BRICKS, 1, POLISHED_ANDESITE, GRASS_BLOCK), new int[] {-1, 0, 1}, 60);
        List<SurveySample> both = new ArrayList<>(wide);
        both.addAll(narrow);

        SurveyStats merged = SurveyStats.of(wide).merge(SurveyStats.of(narrow));
        SurveyStats concatenated = SurveyStats.of(both);

        assertEquals(concatenated, merged);
        assertEquals(learner.learn(concatenated), learner.learn(merged));

        ProposedProfile profile = learner.learn(merged);
        assertEquals(160, profile.sampleCount());
        assertEquals(5, profile.widthMin(), "5th percentile of 60×5 and 100×7");
        assertEquals(7, profile.widthMax());
        assertEquals(RoadMaterialRole.SURFACE, profile.roleOf(STONE_BRICKS).orElseThrow());
        assertEquals(RoadMaterialRole.EDGE, profile.roleOf(POLISHED_ANDESITE).orElseThrow());
    }

    @Test
    void mergingTheStoredStatsWithANewSurveyIsHowPhase3Accumulates() {
        SurveyStats stored = SurveyStats.fromJson("{}"); // the bootstrap profile
        SurveyStats survey1 = SurveyStats.of(CrossSections.stoneBrickRoadWithKerbs(50));
        SurveyStats afterFirst = SurveyStats.fromJson(stored.merge(survey1).toJson());
        SurveyStats survey2 = SurveyStats.of(CrossSections.stoneBrickRoadWithKerbs(50));
        SurveyStats afterSecond = SurveyStats.fromJson(afterFirst.merge(survey2).toJson());

        assertEquals(SurveyStats.of(CrossSections.stoneBrickRoadWithKerbs(100)), afterSecond);
        assertEquals(100, learner.learn(afterSecond).sampleCount());
    }

    @Test
    void emptyStatsProposeNothing() {
        ProposedProfile profile = learner.learn(SurveyStats.empty());

        assertTrue(profile.materials().isEmpty());
        assertEquals(ProfileLearner.MIN_WIDTH, profile.widthMin());
        assertEquals(ProfileLearner.MIN_WIDTH, profile.widthMax());
        assertEquals(0, profile.sampleCount());
    }

    @Test
    void overlaysSeenOnTheFloorGetTheOverlayRole() {
        IntFunction<String> road = CrossSections.road(STONE_BRICKS, 2, POLISHED_ANDESITE, GRASS_BLOCK);
        List<SurveySample> samples = new ArrayList<>();
        for (int x = 0; x < 100; x++) {
            samples.add(CrossSections.sample(road, x % 5 - 2, x, x % 10 < 3 ? SNOW : null));
        }

        ProposedProfile profile = learner.learn(SurveyStats.of(samples));

        Material snow = material(profile, SNOW);
        assertEquals(RoadMaterialRole.OVERLAY, snow.role());
        assertEquals(30, snow.samples());
        assertEquals(0.0, snow.centreShare(), 1e-9);
        assertEquals(0.0, snow.edgeShare(), 1e-9);
        assertFalse(snow.ambiguous());
        assertEquals(List.of(STONE_BRICKS, POLISHED_ANDESITE, SNOW),
            profile.materials().stream().map(Material::material).toList(), "Surface, Edge, Overlay order");
        assertEquals(List.of(snow), profile.withRole(RoadMaterialRole.OVERLAY));
    }

    @Test
    void aNameThatIsMostlyAFloorKeepsItsFloorRoleWhenAlsoSeenAsOverlay() {
        IntFunction<String> mossRoad = CrossSections.road("MOSS_BLOCK", 2, null, GRASS_BLOCK);
        List<SurveySample> samples = new ArrayList<>();
        for (int x = 0; x < 100; x++) {
            samples.add(CrossSections.sample(mossRoad, 0, x, x % 20 == 0 ? "MOSS_BLOCK" : null));
        }

        ProposedProfile profile = learner.learn(SurveyStats.of(samples));

        assertEquals(1, profile.materials().size());
        assertEquals(RoadMaterialRole.SURFACE, material(profile, "MOSS_BLOCK").role());
    }

    @Test
    void rareOverlaysAreDropped() {
        IntFunction<String> road = CrossSections.road(STONE_BRICKS, 2, null, GRASS_BLOCK);
        List<SurveySample> samples = new ArrayList<>();
        for (int x = 0; x < 200; x++) {
            samples.add(CrossSections.sample(road, 0, x, x == 0 ? SNOW : null));
        }

        assertTrue(learner.learn(SurveyStats.of(samples)).material(SNOW).isEmpty(), "1 of 200 < 1%");
    }

    @Test
    void roadLikeMaterialsBelowOnePercentAreDroppedAndBelowFivePercentAreAccents() {
        IntFunction<String> road = CrossSections.road(STONE_BRICKS, 2, POLISHED_ANDESITE, GRASS_BLOCK);
        IntFunction<String> patched = abs -> abs == 0 ? COARSE_DIRT : road.apply(abs);

        List<SurveySample> onePatch = new ArrayList<>(CrossSections.stoneBrickRoadWithKerbs(200));
        onePatch.add(CrossSections.sample(patched, 0, 200));
        assertTrue(learner.learn(SurveyStats.of(onePatch)).material(COARSE_DIRT).isEmpty(), "1 of 201 runs < 1%");

        List<SurveySample> sixPatches = new ArrayList<>(CrossSections.stoneBrickRoadWithKerbs(200));
        for (int x = 0; x < 6; x++) {
            sixPatches.add(CrossSections.sample(patched, 0, 200 + x));
        }
        Material accent = material(learner.learn(SurveyStats.of(sixPatches)), COARSE_DIRT);
        assertEquals(RoadMaterialRole.ACCENT, accent.role(), "6 of 206 runs is under 5%");
        assertEquals(6, accent.samples());
        assertFalse(accent.ambiguous());
    }

    @Test
    void aStreetWideningIntoAPlazaGivesTheMaximumWidthAsWidthMax() {
        List<SurveySample> samples = new ArrayList<>(CrossSections.stoneBrickRoadWithKerbs(20));
        for (int x = 0; x < 10; x++) {
            samples.add(SurveySample.of(20 + x, 64, 0, true, null, Collections.nCopies(SurveySample.WIDTH, STONE_BRICKS)));
        }

        ProposedProfile profile = learner.learn(SurveyStats.of(samples));

        assertEquals(7, profile.widthMin());
        assertEquals(SurveySample.WIDTH, profile.widthMax(), "15 means 'at least the whole cross-section'");
        assertEquals(RoadMaterialRole.SURFACE, material(profile, STONE_BRICKS).role());
        assertEquals(RoadMaterialRole.EDGE, material(profile, POLISHED_ANDESITE).role());
    }

    @Test
    void aSurveyOfOnlyAPlazaLearnsNoMaterials() {
        List<SurveySample> plaza = new ArrayList<>();
        for (int x = 0; x < 20; x++) {
            plaza.add(SurveySample.of(x, 64, 0, true, null, Collections.nCopies(SurveySample.WIDTH, STONE_BRICKS)));
        }

        ProposedProfile profile = learner.learn(SurveyStats.of(plaza));

        assertTrue(profile.materials().isEmpty(), "wider than the cross-section, the surface looks like terrain");
        assertEquals(ProfileLearner.MIN_WIDTH, profile.widthMin());
        assertEquals(20, profile.sampleCount());
    }

    @Test
    void widthsAreTheFifthAndNinetyFifthPercentilesByNearestRank() {
        SortedMap<Integer, Integer> histogram = new TreeMap<>();
        histogram.put(3, 10);
        histogram.put(5, 80);
        histogram.put(7, 10);
        assertEquals(3, ProfileLearner.percentile(histogram, ProfileLearner.WIDTH_MIN_PERCENTILE));
        assertEquals(7, ProfileLearner.percentile(histogram, ProfileLearner.WIDTH_MAX_PERCENTILE));
        assertEquals(5, ProfileLearner.percentile(histogram, 50));

        SortedMap<Integer, Integer> mostlyOne = new TreeMap<>();
        mostlyOne.put(1, 19);
        mostlyOne.put(2, 1);
        assertEquals(1, ProfileLearner.percentile(mostlyOne, 95), "rank 19 of 20 is still width 1");
        assertEquals(2, ProfileLearner.percentile(mostlyOne, 100));

        assertEquals(ProfileLearner.MIN_WIDTH, ProfileLearner.percentile(new TreeMap<>(), 50));
    }

    @Test
    void roadLikenessIsCentreOverCentrePlusOuterWithMidOnlyMaterialsRoadLike() {
        assertEquals(1.0, ProfileLearner.roadLikeness(new MaterialCounts(3, 0, 0, 0, 0, 0, 0)), 1e-9);
        assertEquals(0.0, ProfileLearner.roadLikeness(new MaterialCounts(0, 4, 5, 0, 0, 0, 0)), 1e-9);
        assertEquals(0.6, ProfileLearner.roadLikeness(new MaterialCounts(3, 0, 2, 0, 0, 0, 0)), 1e-9);
        assertTrue(ProfileLearner.isRoadLike(new MaterialCounts(3, 0, 2, 0, 0, 0, 0)), "0.6 is inclusive");
        assertFalse(ProfileLearner.isRoadLike(new MaterialCounts(2, 0, 2, 0, 0, 0, 0)));
        assertEquals(1.0, ProfileLearner.roadLikeness(new MaterialCounts(0, 2, 0, 0, 0, 0, 0)), 1e-9, "mid only");
        assertEquals(0.0, ProfileLearner.roadLikeness(MaterialCounts.ZERO), 1e-9);
        assertFalse(ProfileLearner.isRoadLike(new MaterialCounts(0, 0, 0, 0, 0, 0, 3)), "an overlay is not a floor");
    }

    @Test
    void thresholdsAreThePlannedOnes() {
        assertEquals(0.6, ProfileLearner.ROAD_LIKENESS_MIN, 1e-12);
        assertEquals(0.15, ProfileLearner.SURFACE_MIN_CENTRE_SHARE, 1e-12);
        assertEquals(2.0, ProfileLearner.EDGE_RUN_END_FACTOR, 1e-12);
        assertEquals(0.05, ProfileLearner.ACCENT_MAX_PRESENCE, 1e-12);
        assertEquals(0.10, ProfileLearner.AMBIGUOUS_MIN_OUTSIDE_SHARE, 1e-12);
        assertEquals(0.01, ProfileLearner.DROP_BELOW_PRESENCE, 1e-12);
        assertEquals(5, ProfileLearner.WIDTH_MIN_PERCENTILE);
        assertEquals(95, ProfileLearner.WIDTH_MAX_PERCENTILE);
    }
}
