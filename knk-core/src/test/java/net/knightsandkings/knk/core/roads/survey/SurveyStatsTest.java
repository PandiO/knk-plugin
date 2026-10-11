package net.knightsandkings.knk.core.roads.survey;

import net.knightsandkings.knk.core.roads.survey.SurveyStats.Bucket;
import net.knightsandkings.knk.core.roads.survey.SurveyStats.MaterialCounts;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static net.knightsandkings.knk.core.roads.survey.CrossSections.GRASS_BLOCK;
import static net.knightsandkings.knk.core.roads.survey.CrossSections.GRAVEL;
import static net.knightsandkings.knk.core.roads.survey.CrossSections.POLISHED_ANDESITE;
import static net.knightsandkings.knk.core.roads.survey.CrossSections.SNOW;
import static net.knightsandkings.knk.core.roads.survey.CrossSections.STONE_BRICKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SurveyStats: bucket counts, runs/widths/run ends, outside-run and overlay counts from hand-made
 * cross-sections; merge; the StatsJson v1 round trip.
 */
class SurveyStatsTest {
    private static final String G = GRASS_BLOCK;
    private static final String S = STONE_BRICKS;
    private static final String A = POLISHED_ANDESITE;

    /** −7…+7: G G G G A S S S S S A G G G wall — a 5-wide road with kerbs, the walker at the centre. */
    private static final SurveySample KERBED_ROAD = SurveySample.of(0, 64, 0, true, null,
        Arrays.asList(G, G, G, G, A, S, S, S, S, S, A, G, G, G, null));

    /** All grass with one stone at +6: the walker is off the road, there is no run. */
    private static final SurveySample OFF_ROAD = SurveySample.of(1, 64, 0, true, null,
        Arrays.asList(G, G, G, G, G, G, G, G, G, G, G, G, G, "STONE", G));

    @Test
    void bucketsSplitTheCrossSectionAtOneAndFive() {
        assertEquals(Bucket.CENTRE, Bucket.of(0));
        assertEquals(Bucket.CENTRE, Bucket.of(-1));
        assertEquals(Bucket.CENTRE, Bucket.of(1));
        assertEquals(Bucket.MID, Bucket.of(2));
        assertEquals(Bucket.MID, Bucket.of(-5));
        assertEquals(Bucket.OUTER, Bucket.of(6));
        assertEquals(Bucket.OUTER, Bucket.of(-7));
    }

    @Test
    void countsCellsPerBucketAndPerMaterial() {
        SurveyStats stats = SurveyStats.of(List.of(KERBED_ROAD));

        assertEquals(1, stats.samples());
        assertEquals(3, stats.centreCells());
        assertEquals(8, stats.midCells());
        assertEquals(3, stats.outerCells(), "the wall at +7 is not a cell");
        assertEquals(new MaterialCounts(3, 2, 0, 0, 1, 0, 0), stats.counts(S));
        assertEquals(new MaterialCounts(0, 2, 0, 2, 1, 0, 0), stats.counts(A));
        assertEquals(new MaterialCounts(0, 4, 3, 0, 0, 1, 0), stats.counts(G));
        assertEquals(MaterialCounts.ZERO, stats.counts("NEVER_SEEN"));
        assertEquals(List.of(G, A, S), List.copyOf(stats.materials().keySet()), "sorted by name");
    }

    @Test
    void runSpansTheRoadLikeStretchAroundTheCentreAndEndsAtTheKerbs() {
        SurveyStats stats = SurveyStats.of(List.of(KERBED_ROAD));

        assertEquals(1, stats.runs());
        assertEquals(Map.of(7, 1), stats.widths(), "S S S S S plus a kerb on each side");
        assertEquals(2, stats.counts(A).runEnd(), "both run ends are andesite");
        assertEquals(2, stats.runEndSlots());
        assertEquals(0, stats.counts(S).runEnd());
        assertEquals(1, stats.counts(G).outsideRun(), "grass sits in the outer bucket outside the run");
        assertEquals(0, stats.counts(S).outsideRun());
    }

    @Test
    void kerbSeenOnlyInTheMidBucketIsRoadLikeAndJoinsTheRun() {
        // andesite: centre 0, outer 0, mid 2 — never at the walker's feet, never at the far sides
        assertTrue(ProfileLearner.isRoadLike(SurveyStats.of(List.of(KERBED_ROAD)).counts(A)));
        assertEquals(1, SurveyStats.of(List.of(KERBED_ROAD)).counts(A).inRun());
    }

    @Test
    void sampleWithoutRoadLikeCentreHasNoRunButStillCountsOutsideAppearances() {
        SurveyStats stats = SurveyStats.of(List.of(OFF_ROAD));

        assertEquals(1, stats.samples());
        assertEquals(0, stats.runs());
        assertTrue(stats.widths().isEmpty());
        assertEquals(0, stats.runEndSlots());
        assertEquals(new MaterialCounts(3, 8, 3, 0, 0, 1, 0), stats.counts(G));
        assertEquals(new MaterialCounts(0, 0, 1, 0, 0, 1, 0), stats.counts("STONE"));
    }

    @Test
    void oneWideRunCountsItsSingleEndOnce() {
        SurveySample path = CrossSections.sample(CrossSections.road(GRAVEL, 0, null, G), 0, 0);
        SurveyStats stats = SurveyStats.of(List.of(path));

        assertEquals(Map.of(1, 1), stats.widths());
        assertEquals(1, stats.counts(GRAVEL).runEnd());
        assertEquals(1, stats.counts(GRAVEL).inRun());
        assertEquals(0, stats.counts(G).inRun());
    }

    @Test
    void runCanCoverTheWholeCrossSectionWhereTheRoadWidensIntoAPlaza() {
        List<SurveySample> samples = new ArrayList<>(CrossSections.stoneBrickRoadWithKerbs(20));
        samples.add(SurveySample.of(20, 64, 0, true, null, Collections.nCopies(SurveySample.WIDTH, S)));
        SurveyStats stats = SurveyStats.of(samples);

        assertEquals(Map.of(7, 20, SurveySample.WIDTH, 1), stats.widths());
        assertEquals(2, stats.counts(S).runEnd(), "the plaza sample's run ends on stone bricks");
        assertEquals(0, stats.counts(S).outsideRun(), "outer cells inside the run are not 'outside'");
        assertEquals(4, stats.counts(S).outer());
    }

    @Test
    void aSurveyOfNothingButAPlazaSeesItsSurfaceAsTerrain() {
        // Wider than the cross-section, the surface fills the outer bucket too: r = 3/7 < 0.6. Documented
        // limit of the centre-vs-outer rule (plan Phase 2b status); plazas are learned from the streets
        // that lead into them.
        SurveySample plaza = SurveySample.of(0, 64, 0, true, null, Collections.nCopies(SurveySample.WIDTH, S));
        SurveyStats stats = SurveyStats.of(List.of(plaza));

        assertFalse(ProfileLearner.isRoadLike(stats.counts(S)));
        assertEquals(0, stats.runs());
        assertTrue(stats.widths().isEmpty());
    }

    @Test
    void wallsStopTheRun() {
        String planks = "OAK_PLANKS"; // house floors beyond the walls
        SurveySample walled = SurveySample.of(0, 64, 0, true, null,
            Arrays.asList(planks, planks, planks, planks, null, S, S, S, S, S, S, null, planks, planks, planks));
        SurveyStats stats = SurveyStats.of(List.of(walled));

        assertEquals(Map.of(6, 1), stats.widths(), "-2..+3 between the walls");
        assertEquals(2, stats.counts(S).runEnd());
        assertEquals(0, stats.counts(S).outsideRun());
        assertEquals(1, stats.counts(planks).outsideRun(), "the floor beyond the walls is outside the run");
        assertFalse(ProfileLearner.isRoadLike(stats.counts(planks)));
    }

    @Test
    void overlaysAreCountedPerSampleNotAsFloorCells() {
        SurveySample snowy = CrossSections.sample(CrossSections.road(S, 2, A, G), 0, 0, SNOW);
        SurveyStats stats = SurveyStats.of(List.of(snowy, KERBED_ROAD));

        assertEquals(new MaterialCounts(0, 0, 0, 0, 0, 0, 1), stats.counts(SNOW));
        assertEquals(6, stats.centreCells(), "the overlay adds no cells");
    }

    @Test
    void ofTheConcatenationEqualsTheMergeWhenBothBatchesClassifyAlike() {
        SurveyStats both = SurveyStats.of(List.of(KERBED_ROAD, OFF_ROAD));
        SurveyStats merged = SurveyStats.of(List.of(KERBED_ROAD)).merge(SurveyStats.of(List.of(OFF_ROAD)));

        assertEquals(both, merged);
        assertEquals(both.hashCode(), merged.hashCode());
        assertEquals(2, merged.samples());
        assertEquals(1, merged.runs());
        assertEquals(new MaterialCounts(3, 12, 6, 0, 0, 2, 0), merged.counts(G));
    }

    @Test
    void mergeIsCommutativeLeavesInputsUntouchedAndHasEmptyAsIdentity() {
        SurveyStats a = SurveyStats.of(List.of(KERBED_ROAD));
        SurveyStats b = SurveyStats.of(List.of(OFF_ROAD));
        SurveyStats ab = a.merge(b);

        assertEquals(ab, b.merge(a));
        assertEquals(SurveyStats.of(List.of(KERBED_ROAD)), a, "merge did not mutate its receiver");
        assertEquals(a, a.merge(SurveyStats.empty()));
        assertEquals(a, SurveyStats.empty().merge(a));
        assertTrue(SurveyStats.empty().isEmpty());
        assertFalse(a.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> ab.widths().put(3, 1));
        assertThrows(UnsupportedOperationException.class, () -> ab.materials().remove(G));
    }

    @Test
    void jsonRoundTripIsLossless() {
        SurveyStats stats = SurveyStats.of(CrossSections.stoneBrickRoadWithKerbs(25))
            .merge(SurveyStats.of(List.of(CrossSections.sample(CrossSections.road(S, 2, A, G), 0, 99, SNOW))));

        String json = stats.toJson();
        SurveyStats back = SurveyStats.fromJson(json);

        assertEquals(stats, back);
        assertEquals(json, back.toJson(), "serialisation is deterministic");
        assertTrue(json.startsWith("{\"version\":1,\"samples\":26,\"runs\":26,\"cells\":{\"centre\":78,"), json);
        assertTrue(json.contains("\"STONE_BRICKS\":{\"centre\":"), json);
        assertTrue(json.contains("\"widths\":{\"7\":26}"), json);
        assertTrue(json.contains("\"SNOW\":{\"centre\":0,\"mid\":0,\"outer\":0,\"runEnd\":0,\"inRun\":0,\"outsideRun\":0,\"overlay\":1}"), json);
    }

    @Test
    void emptyObjectNullAndBlankReadAsEmptyStats() {
        assertEquals(SurveyStats.empty(), SurveyStats.fromJson("{}"), "the bootstrap profile's StatsJson");
        assertEquals(SurveyStats.empty(), SurveyStats.fromJson(null));
        assertEquals(SurveyStats.empty(), SurveyStats.fromJson("   "));
        assertEquals(SurveyStats.empty(), SurveyStats.fromJson(SurveyStats.empty().toJson()));
        assertEquals("{\"version\":1,\"samples\":0,\"runs\":0,\"cells\":{\"centre\":0,\"mid\":0,\"outer\":0},"
            + "\"materials\":{},\"widths\":{}}", SurveyStats.empty().toJson());
    }

    @Test
    void jsonWithMissingSectionsAndUnknownKeysStillParses() {
        SurveyStats stats = SurveyStats.fromJson("{\"version\":1,\"samples\":3,\"future\":true,"
            + "\"materials\":{\"GRAVEL\":{\"centre\":3,\"extra\":9}}}");

        assertEquals(3, stats.samples());
        assertEquals(0, stats.runs());
        assertEquals(new MaterialCounts(3, 0, 0, 0, 0, 0, 0), stats.counts(GRAVEL));
        assertTrue(stats.widths().isEmpty());
    }

    @Test
    void jsonWithWrongVersionOrShapeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SurveyStats.fromJson("{\"version\":2,\"samples\":1}"));
        assertThrows(IllegalArgumentException.class, () -> SurveyStats.fromJson("{\"samples\":1}"), "no version");
        assertThrows(IllegalArgumentException.class, () -> SurveyStats.fromJson("{\"version\":1,\"samples\":-1}"));
        assertThrows(IllegalArgumentException.class, () -> SurveyStats.fromJson("{\"version\":1,\"materials\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> SurveyStats.fromJson("{\"version\":1,\"widths\":{\"wide\":1}}"));
        assertThrows(IllegalArgumentException.class, () -> SurveyStats.fromJson("{\"version\":1,\"widths\":{\"0\":1}}"));
        assertThrows(IllegalArgumentException.class, () -> SurveyStats.fromJson("not json"));
        assertThrows(IllegalArgumentException.class, () -> SurveyStats.fromJson("[1,2]"));
    }

    @Test
    void materialCountsRejectNegativesAndAddUp() {
        assertThrows(IllegalArgumentException.class, () -> new MaterialCounts(-1, 0, 0, 0, 0, 0, 0));
        MaterialCounts sum = new MaterialCounts(1, 2, 3, 4, 5, 6, 7).plus(new MaterialCounts(1, 1, 1, 1, 1, 1, 1));
        assertEquals(new MaterialCounts(2, 3, 4, 5, 6, 7, 8), sum);
        assertEquals(9, sum.cells());
        assertNotEquals(SurveyStats.of(List.of(KERBED_ROAD)), SurveyStats.of(List.of(OFF_ROAD)));
    }
}
