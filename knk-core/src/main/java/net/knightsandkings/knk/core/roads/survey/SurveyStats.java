package net.knightsandkings.knk.core.roads.survey;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Accumulated statistics of survey samples (DESIGN §5.3, plan Phase 2b): per material the number of
 * cross-section cells per |offset| bucket (centre 0-1, mid 2-5, outer 6-7), run-end, in-run,
 * outside-run and overlay counts; the road-width histogram; sample and cell totals. Immutable;
 * {@link #merge(SurveyStats)} adds two, {@link #toJson()}/{@link #fromJson(String)} is the
 * {@code RoadProfile.StatsJson} / {@code RoadSurvey.StatsJson} form (plan D5, version {@value #VERSION}).
 *
 * <p><b>Runs.</b> A sample's <i>run</i> is the contiguous stretch of cells around offset 0 whose
 * materials are road-like ({@link ProfileLearner#isRoadLike}); it stops at the first non-road-like or
 * null cell on each side. Its length is the sample's width. Because road-likeness needs the whole
 * survey's bucket counts, {@link #of(Collection)} works in two passes over the samples: buckets and
 * overlays first, then runs using the road-likeness of that batch. Merging two batches therefore
 * equals building one batch exactly when both classify the materials the same way (true for surveys
 * of the same kind of road; test scenario d).
 *
 * <p><b>StatsJson v1</b> (flat, string material names, integer counts):
 * <pre>
 * {"version":1,"samples":120,"runs":118,
 *  "cells":{"centre":360,"mid":940,"outer":470},
 *  "materials":{"STONE_BRICKS":{"centre":300,"mid":400,"outer":0,"runEnd":10,"inRun":118,"outsideRun":0,"overlay":0}, …},
 *  "widths":{"4":8,"5":110}}
 * </pre>
 * {@code {}} (the bootstrap profile) and blank input read as {@link #empty()}; unknown keys are ignored.
 */
public final class SurveyStats {

    /** Version of the JSON layout this class writes; {@link #fromJson(String)} refuses others. */
    public static final int VERSION = 1;
    /** |offset| ≤ this is the centre bucket. */
    public static final int CENTRE_MAX_ABS_OFFSET = 1;
    /** CENTRE_MAX_ABS_OFFSET &lt; |offset| ≤ this is the mid bucket; beyond it is the outer bucket. */
    public static final int MID_MAX_ABS_OFFSET = 5;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    /** |offset| bucket of a cross-section cell. */
    public enum Bucket {
        CENTRE, MID, OUTER;

        public static Bucket of(int offset) {
            int abs = Math.abs(offset);
            if (abs <= CENTRE_MAX_ABS_OFFSET) {
                return CENTRE;
            }
            return abs <= MID_MAX_ABS_OFFSET ? MID : OUTER;
        }
    }

    /**
     * Counts of one material.
     *
     * @param centre     cells at |offset| 0-1
     * @param mid        cells at |offset| 2-5
     * @param outer      cells at |offset| 6-7
     * @param runEnd     times the material was the outermost cell of a run (one per run end; a 1-wide
     *                   run has one end)
     * @param inRun      samples whose run contained the material (at most one per sample)
     * @param outsideRun samples in which the material appeared in the outer bucket outside the run
     *                   (at most one per sample; the ambiguity evidence of DESIGN §5.3)
     * @param overlay    samples whose overlay was the material
     */
    public record MaterialCounts(int centre, int mid, int outer, int runEnd, int inRun, int outsideRun, int overlay) {
        public static final MaterialCounts ZERO = new MaterialCounts(0, 0, 0, 0, 0, 0, 0);

        public MaterialCounts {
            requireNonNegative(centre, "centre");
            requireNonNegative(mid, "mid");
            requireNonNegative(outer, "outer");
            requireNonNegative(runEnd, "runEnd");
            requireNonNegative(inRun, "inRun");
            requireNonNegative(outsideRun, "outsideRun");
            requireNonNegative(overlay, "overlay");
        }

        public MaterialCounts plus(MaterialCounts o) {
            return new MaterialCounts(centre + o.centre, mid + o.mid, outer + o.outer, runEnd + o.runEnd,
                inRun + o.inRun, outsideRun + o.outsideRun, overlay + o.overlay);
        }

        /** Cross-section cells in any bucket. */
        public int cells() {
            return centre + mid + outer;
        }

        MaterialCounts withBucket(Bucket bucket, int delta) {
            return switch (bucket) {
                case CENTRE -> new MaterialCounts(centre + delta, mid, outer, runEnd, inRun, outsideRun, overlay);
                case MID -> new MaterialCounts(centre, mid + delta, outer, runEnd, inRun, outsideRun, overlay);
                case OUTER -> new MaterialCounts(centre, mid, outer + delta, runEnd, inRun, outsideRun, overlay);
            };
        }

        MaterialCounts withRunEnd(int delta) {
            return new MaterialCounts(centre, mid, outer, runEnd + delta, inRun, outsideRun, overlay);
        }

        MaterialCounts withInRun(int delta) {
            return new MaterialCounts(centre, mid, outer, runEnd, inRun + delta, outsideRun, overlay);
        }

        MaterialCounts withOutsideRun(int delta) {
            return new MaterialCounts(centre, mid, outer, runEnd, inRun, outsideRun + delta, overlay);
        }

        MaterialCounts withOverlay(int delta) {
            return new MaterialCounts(centre, mid, outer, runEnd, inRun, outsideRun, overlay + delta);
        }
    }

    private final int samples;
    private final int runs;
    private final int centreCells;
    private final int midCells;
    private final int outerCells;
    private final SortedMap<String, MaterialCounts> materials;
    private final SortedMap<Integer, Integer> widths;

    private SurveyStats(int samples, int runs, int centreCells, int midCells, int outerCells,
                        SortedMap<String, MaterialCounts> materials, SortedMap<Integer, Integer> widths) {
        requireNonNegative(samples, "samples");
        requireNonNegative(runs, "runs");
        requireNonNegative(centreCells, "centreCells");
        requireNonNegative(midCells, "midCells");
        requireNonNegative(outerCells, "outerCells");
        this.samples = samples;
        this.runs = runs;
        this.centreCells = centreCells;
        this.midCells = midCells;
        this.outerCells = outerCells;
        this.materials = Collections.unmodifiableSortedMap(materials);
        this.widths = Collections.unmodifiableSortedMap(widths);
    }

    /** No samples at all — what a profile has before its first survey (StatsJson {@code {}}). */
    public static SurveyStats empty() {
        return new SurveyStats(0, 0, 0, 0, 0, new TreeMap<>(), new TreeMap<>());
    }

    /** Builds the statistics of one survey (two passes, see the class comment). */
    public static SurveyStats of(Collection<SurveySample> samples) {
        Objects.requireNonNull(samples, "samples");
        Map<String, MaterialCounts> counts = new HashMap<>();
        int centreCells = 0;
        int midCells = 0;
        int outerCells = 0;

        // Pass 1: bucket counts and overlays.
        for (SurveySample sample : samples) {
            for (int offset = SurveySample.MIN_OFFSET; offset <= SurveySample.MAX_OFFSET; offset++) {
                String material = sample.materialAt(offset);
                if (material == null) {
                    continue;
                }
                Bucket bucket = Bucket.of(offset);
                switch (bucket) {
                    case CENTRE -> centreCells++;
                    case MID -> midCells++;
                    case OUTER -> outerCells++;
                }
                counts.merge(material, MaterialCounts.ZERO.withBucket(bucket, 1), MaterialCounts::plus);
            }
            if (sample.hasOverlay()) {
                counts.merge(sample.overlay(), MaterialCounts.ZERO.withOverlay(1), MaterialCounts::plus);
            }
        }

        // Road-likeness of this batch, then pass 2: runs, run ends, widths, outside-run appearances.
        Set<String> roadLike = new HashSet<>();
        for (Map.Entry<String, MaterialCounts> entry : counts.entrySet()) {
            if (ProfileLearner.isRoadLike(entry.getValue())) {
                roadLike.add(entry.getKey());
            }
        }
        int runs = 0;
        SortedMap<Integer, Integer> widths = new TreeMap<>();
        for (SurveySample sample : samples) {
            int left = 0;
            int right = -1; // empty run until the centre proves road-like
            if (roadLike.contains(sample.floor())) {
                left = 0;
                right = 0;
                while (left > SurveySample.MIN_OFFSET && roadLike.contains(sample.materialAt(left - 1))) {
                    left--;
                }
                while (right < SurveySample.MAX_OFFSET && roadLike.contains(sample.materialAt(right + 1))) {
                    right++;
                }
                runs++;
                widths.merge(right - left + 1, 1, Integer::sum);
                counts.merge(sample.materialAt(left), MaterialCounts.ZERO.withRunEnd(1), MaterialCounts::plus);
                if (right != left) {
                    counts.merge(sample.materialAt(right), MaterialCounts.ZERO.withRunEnd(1), MaterialCounts::plus);
                }
                Set<String> inRun = new HashSet<>();
                for (int offset = left; offset <= right; offset++) {
                    inRun.add(sample.materialAt(offset));
                }
                for (String material : inRun) {
                    counts.merge(material, MaterialCounts.ZERO.withInRun(1), MaterialCounts::plus);
                }
            }
            Set<String> outside = new HashSet<>();
            for (int offset = SurveySample.MIN_OFFSET; offset <= SurveySample.MAX_OFFSET; offset++) {
                if (Bucket.of(offset) != Bucket.OUTER || (offset >= left && offset <= right)) {
                    continue;
                }
                String material = sample.materialAt(offset);
                if (material != null) {
                    outside.add(material);
                }
            }
            for (String material : outside) {
                counts.merge(material, MaterialCounts.ZERO.withOutsideRun(1), MaterialCounts::plus);
            }
        }
        return new SurveyStats(samples.size(), runs, centreCells, midCells, outerCells, new TreeMap<>(counts), widths);
    }

    /** Sum of this and {@code other}; neither input changes. */
    public SurveyStats merge(SurveyStats other) {
        Objects.requireNonNull(other, "other");
        SortedMap<String, MaterialCounts> mergedMaterials = new TreeMap<>(materials);
        other.materials.forEach((material, counts) -> mergedMaterials.merge(material, counts, MaterialCounts::plus));
        SortedMap<Integer, Integer> mergedWidths = new TreeMap<>(widths);
        other.widths.forEach((width, count) -> mergedWidths.merge(width, count, Integer::sum));
        return new SurveyStats(samples + other.samples, runs + other.runs, centreCells + other.centreCells,
            midCells + other.midCells, outerCells + other.outerCells, mergedMaterials, mergedWidths);
    }

    /** Total samples. */
    public int samples() {
        return samples;
    }

    /** Samples whose centre cell was road-like, i.e. that have a width. */
    public int runs() {
        return runs;
    }

    /** Non-null cells at |offset| 0-1 over all samples. */
    public int centreCells() {
        return centreCells;
    }

    /** Non-null cells at |offset| 2-5 over all samples. */
    public int midCells() {
        return midCells;
    }

    /** Non-null cells at |offset| 6-7 over all samples. */
    public int outerCells() {
        return outerCells;
    }

    /** Counts per material, sorted by name; unmodifiable. */
    public SortedMap<String, MaterialCounts> materials() {
        return materials;
    }

    /** Counts of one material; {@link MaterialCounts#ZERO} when never seen. */
    public MaterialCounts counts(String material) {
        return materials.getOrDefault(material, MaterialCounts.ZERO);
    }

    /** Width → number of samples with that run length; unmodifiable, sorted by width. */
    public SortedMap<Integer, Integer> widths() {
        return widths;
    }

    /** Total run-end slots (the denominator of a material's edge share). */
    public int runEndSlots() {
        int total = 0;
        for (MaterialCounts counts : materials.values()) {
            total += counts.runEnd();
        }
        return total;
    }

    public boolean isEmpty() {
        return samples == 0 && materials.isEmpty();
    }

    /** The StatsJson v1 form (see the class comment). */
    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("samples", samples);
        root.put("runs", runs);
        Map<String, Object> cells = new LinkedHashMap<>();
        cells.put("centre", centreCells);
        cells.put("mid", midCells);
        cells.put("outer", outerCells);
        root.put("cells", cells);
        Map<String, Object> materialsJson = new LinkedHashMap<>();
        materials.forEach((material, counts) -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("centre", counts.centre());
            c.put("mid", counts.mid());
            c.put("outer", counts.outer());
            c.put("runEnd", counts.runEnd());
            c.put("inRun", counts.inRun());
            c.put("outsideRun", counts.outsideRun());
            c.put("overlay", counts.overlay());
            materialsJson.put(material, c);
        });
        root.put("materials", materialsJson);
        Map<String, Object> widthsJson = new LinkedHashMap<>();
        widths.forEach((width, count) -> widthsJson.put(Integer.toString(width), count));
        root.put("widths", widthsJson);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("SurveyStats could not be serialised", e);
        }
    }

    /**
     * Parses the StatsJson form. Null, blank or {@code {}} → {@link #empty()}.
     *
     * @throws IllegalArgumentException on malformed JSON, a missing or unsupported version, or negative counts
     */
    public static SurveyStats fromJson(String json) {
        if (json == null || json.isBlank()) {
            return empty();
        }
        Map<String, Object> root;
        try {
            root = MAPPER.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("StatsJson is not a JSON object: " + e.getOriginalMessage(), e);
        }
        if (root == null || root.isEmpty()) {
            return empty();
        }
        Object version = root.get("version");
        if (!(version instanceof Number)) {
            throw new IllegalArgumentException("StatsJson has no numeric version");
        }
        if (((Number) version).intValue() != VERSION) {
            throw new IllegalArgumentException("StatsJson version " + version + " is not supported (expected " + VERSION + ")");
        }
        Map<String, Object> cells = objectOf(root.get("cells"));
        SortedMap<String, MaterialCounts> materials = new TreeMap<>();
        objectOf(root.get("materials")).forEach((material, value) -> {
            Map<String, Object> c = objectOf(value);
            materials.put(material, new MaterialCounts(intOf(c, "centre"), intOf(c, "mid"), intOf(c, "outer"),
                intOf(c, "runEnd"), intOf(c, "inRun"), intOf(c, "outsideRun"), intOf(c, "overlay")));
        });
        SortedMap<Integer, Integer> widths = new TreeMap<>();
        objectOf(root.get("widths")).forEach((width, count) -> {
            int w;
            try {
                w = Integer.parseInt(width);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("StatsJson width key is not an integer: " + width, e);
            }
            if (w < 1) {
                throw new IllegalArgumentException("StatsJson width must be >= 1: " + width);
            }
            widths.put(w, intOf(count, "widths." + width));
        });
        return new SurveyStats(intOf(root, "samples"), intOf(root, "runs"), intOf(cells, "centre"),
            intOf(cells, "mid"), intOf(cells, "outer"), materials, widths);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectOf(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("StatsJson: expected an object, got " + value.getClass().getSimpleName());
        }
        return (Map<String, Object>) value;
    }

    private static int intOf(Map<String, Object> object, String key) {
        return intOf(object.get(key), key);
    }

    private static int intOf(Object value, String key) {
        if (value == null) {
            return 0;
        }
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("StatsJson: " + key + " is not a number");
        }
        int result = ((Number) value).intValue();
        if (result < 0) {
            throw new IllegalArgumentException("StatsJson: " + key + " is negative");
        }
        return result;
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + value);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SurveyStats other)) {
            return false;
        }
        return samples == other.samples && runs == other.runs && centreCells == other.centreCells
            && midCells == other.midCells && outerCells == other.outerCells
            && materials.equals(other.materials) && widths.equals(other.widths);
    }

    @Override
    public int hashCode() {
        return Objects.hash(samples, runs, centreCells, midCells, outerCells, materials, widths);
    }

    @Override
    public String toString() {
        return "SurveyStats{samples=" + samples + ", runs=" + runs + ", materials=" + materials.keySet()
            + ", widths=" + widths + '}';
    }
}
