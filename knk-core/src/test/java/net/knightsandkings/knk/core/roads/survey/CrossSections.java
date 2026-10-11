package net.knightsandkings.knk.core.roads.survey;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Test helper: synthetic survey walks. A road is described in <i>absolute</i> lateral positions
 * (0 = the road's centre line); a sample is the cross-section seen from a walker standing at an
 * absolute lateral offset, so the road appears shifted in the sample's relative offsets −7…+7 —
 * exactly what wandering across the road does in a real survey.
 */
final class CrossSections {
    static final String STONE_BRICKS = "STONE_BRICKS";
    static final String POLISHED_ANDESITE = "POLISHED_ANDESITE";
    static final String COBBLESTONE = "COBBLESTONE";
    static final String GRAVEL = "GRAVEL";
    static final String GRASS_BLOCK = "GRASS_BLOCK";
    static final String PODZOL = "PODZOL";
    static final String COARSE_DIRT = "COARSE_DIRT";
    static final String SNOW = "SNOW";

    private CrossSections() {
    }

    /** The cross-section a walker at {@code walkerAbs} sees of the road {@code roadAt} (absolute → material). */
    static List<String> section(IntFunction<String> roadAt, int walkerAbs) {
        List<String> offsets = new ArrayList<>(SurveySample.WIDTH);
        for (int rel = SurveySample.MIN_OFFSET; rel <= SurveySample.MAX_OFFSET; rel++) {
            offsets.add(roadAt.apply(walkerAbs + rel));
        }
        return offsets;
    }

    /** One sample at x = {@code x}, no overlay. */
    static SurveySample sample(IntFunction<String> roadAt, int walkerAbs, int x) {
        return SurveySample.of(x, 64, 0, true, null, section(roadAt, walkerAbs));
    }

    /** One sample with an overlay lying on the floor. */
    static SurveySample sample(IntFunction<String> roadAt, int walkerAbs, int x, String overlay) {
        return SurveySample.of(x, 64, 0, true, overlay, section(roadAt, walkerAbs));
    }

    /**
     * {@code count} samples along x, the walker cycling through {@code walkerOffsets}; the road may vary
     * with x ({@code roadAtX.apply(x)} gives the row for that x).
     */
    static List<SurveySample> walkVarying(IntFunction<IntFunction<String>> roadAtX, int[] walkerOffsets, int count) {
        List<SurveySample> samples = new ArrayList<>(count);
        for (int x = 0; x < count; x++) {
            samples.add(sample(roadAtX.apply(x), walkerOffsets[x % walkerOffsets.length], x));
        }
        return samples;
    }

    /** Same road for every x. */
    static List<SurveySample> walk(IntFunction<String> roadAt, int[] walkerOffsets, int count) {
        return walkVarying(x -> roadAt, walkerOffsets, count);
    }

    /** A symmetric road: {@code surface} for |abs| ≤ halfWidth, {@code kerb} at |abs| = halfWidth + 1 (if non-null), terrain beyond. */
    static IntFunction<String> road(String surface, int halfWidth, String kerb, String terrain) {
        return abs -> {
            int a = Math.abs(abs);
            if (a <= halfWidth) {
                return surface;
            }
            if (kerb != null && a == halfWidth + 1) {
                return kerb;
            }
            return terrain;
        };
    }

    /** Scenario (a): 5-wide stone-brick road, polished-andesite kerbs at ±3, grass beyond; walker wanders −2…+2. */
    static List<SurveySample> stoneBrickRoadWithKerbs(int count) {
        return walk(road(STONE_BRICKS, 2, POLISHED_ANDESITE, GRASS_BLOCK), new int[] {-2, -1, 0, 1, 2}, count);
    }

    /**
     * Scenario (b): 1-wide gravel path through a forest: grass either side, a tree trunk (no standable
     * cell) at +2 every third sample, a podzol patch at −4 every fifth sample; the walker stays on the path.
     */
    static List<SurveySample> gravelPathInForest(int count) {
        IntFunction<IntFunction<String>> forest = x -> abs -> {
            if (abs == 0) {
                return GRAVEL;
            }
            if (abs == 2 && x % 3 == 0) {
                return null;
            }
            if (abs == -4 && x % 5 == 0) {
                return PODZOL;
            }
            return GRASS_BLOCK;
        };
        return walkVarying(forest, new int[] {0}, count);
    }

    /**
     * Scenario (c): 5-wide stone-brick road with cobblestone kerbs at ±3 and grass beyond; on every
     * {@code courtyardEvery}-th stretch a cobblestone courtyard lies at abs ≥ +6 (a 2-wide grass strip
     * between the kerb and the courtyard keeps it outside the road run). Walker wanders −2…+2; one
     * stretch = one full walker cycle (5 samples).
     */
    static List<SurveySample> cobbleKerbNextToCobbleCourtyard(int count, int courtyardEvery) {
        IntFunction<String> plain = road(STONE_BRICKS, 2, COBBLESTONE, GRASS_BLOCK);
        IntFunction<String> withCourtyard = abs -> abs >= 6 ? COBBLESTONE : plain.apply(abs);
        IntFunction<IntFunction<String>> street = x -> ((x / 5) % courtyardEvery == courtyardEvery - 1) ? withCourtyard : plain;
        return walkVarying(street, new int[] {-2, -1, 0, 1, 2}, count);
    }
}
