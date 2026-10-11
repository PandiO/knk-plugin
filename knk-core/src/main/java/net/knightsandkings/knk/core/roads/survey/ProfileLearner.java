package net.knightsandkings.knk.core.roads.survey;

import net.knightsandkings.knk.core.domain.roads.RoadMaterialRole;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;

/**
 * Learns a road profile from accumulated survey statistics (DESIGN §5.3 "Learning", plan Phase 2b).
 * Unsupervised, from how materials are distributed across the cross-section:
 * <ul>
 *   <li><b>Road-likeness</b> {@code r = centre / (centre + outer)} per material; a material is road-like
 *       when {@code r ≥} {@link #ROAD_LIKENESS_MIN}. A material never seen in either bucket (kerbs the
 *       admin never stepped next to, seen only at |offset| 2-5) counts as road-like: terrain would
 *       have shown up at the far sides too.</li>
 *   <li><b>Runs</b> (computed by {@link SurveyStats#of}) give widths, run ends and in-run presence.</li>
 *   <li><b>Roles</b>, in this order for each road-like material with presence ≥ {@link #DROP_BELOW_PRESENCE}:
 *       presence &lt; {@link #ACCENT_MAX_PRESENCE} → Accent; edge share ≥ {@link #EDGE_RUN_END_FACTOR} ×
 *       centre share → Edge; centre share ≥ {@link #SURFACE_MIN_CENTRE_SHARE} → Surface; else Accent.
 *       Materials seen lying on the floor → Overlay.</li>
 *   <li><b>Ambiguous</b> when the material appeared in the outer bucket outside the run in ≥
 *       {@link #AMBIGUOUS_MIN_OUTSIDE_SHARE} of the samples.</li>
 *   <li><b>Widths</b>: {@link #WIDTH_MIN_PERCENTILE}th / {@link #WIDTH_MAX_PERCENTILE}th percentile of the
 *       run widths (nearest rank), never below {@link #MIN_WIDTH}.</li>
 * </ul>
 * Shares: centre share = the material's centre cells over the centre cells of all road-like materials;
 * edge share = its run ends over all run ends; presence = samples whose run contained it over samples
 * with a run. The proposal is a starting point the admin reviews (Phase 3 chat, Phase 5 editor).
 */
public final class ProfileLearner {

    /** Minimum road-likeness {@code centre / (centre + outer)} for a material to belong to the road. */
    public static final double ROAD_LIKENESS_MIN = 0.6;
    /** Minimum share of the road's centre cells for the Surface role. */
    public static final double SURFACE_MIN_CENTRE_SHARE = 0.15;
    /** Edge when the share at run ends is at least this many times the centre share. */
    public static final double EDGE_RUN_END_FACTOR = 2.0;
    /** Road-like materials present in fewer than this share of runs are Accents. */
    public static final double ACCENT_MAX_PRESENCE = 0.05;
    /** Ambiguous when seen in the outer bucket outside the run in at least this share of samples. */
    public static final double AMBIGUOUS_MIN_OUTSIDE_SHARE = 0.10;
    /** Materials (and overlays) present in fewer than this share of runs (samples) are dropped as noise. */
    public static final double DROP_BELOW_PRESENCE = 0.01;
    /** Percentile of the run widths that becomes {@code WidthMin}. */
    public static final int WIDTH_MIN_PERCENTILE = 5;
    /** Percentile of the run widths that becomes {@code WidthMax}. */
    public static final int WIDTH_MAX_PERCENTILE = 95;
    /** Smallest width ever proposed (also the width of a survey without any run). */
    public static final int MIN_WIDTH = 1;

    /** Road-likeness of a material from its bucket counts (see the class comment). */
    public static double roadLikeness(SurveyStats.MaterialCounts counts) {
        int seen = counts.centre() + counts.outer();
        if (seen == 0) {
            return counts.mid() > 0 ? 1.0 : 0.0;
        }
        return (double) counts.centre() / seen;
    }

    /** True when {@link #roadLikeness} reaches {@link #ROAD_LIKENESS_MIN}. */
    public static boolean isRoadLike(SurveyStats.MaterialCounts counts) {
        return roadLikeness(counts) >= ROAD_LIKENESS_MIN;
    }

    /** Proposes a profile; an empty {@code stats} yields no materials and widths {@value #MIN_WIDTH}. */
    public ProposedProfile learn(SurveyStats stats) {
        Objects.requireNonNull(stats, "stats");
        int samples = stats.samples();
        int runs = stats.runs();
        int runEndSlots = stats.runEndSlots();

        Map<String, SurveyStats.MaterialCounts> roadLike = new LinkedHashMap<>();
        int roadCentreCells = 0;
        for (Map.Entry<String, SurveyStats.MaterialCounts> entry : stats.materials().entrySet()) {
            SurveyStats.MaterialCounts counts = entry.getValue();
            if (counts.cells() > 0 && isRoadLike(counts)) {
                roadLike.put(entry.getKey(), counts);
                roadCentreCells += counts.centre();
            }
        }

        Map<String, ProposedProfile.Material> proposed = new LinkedHashMap<>();
        for (Map.Entry<String, SurveyStats.MaterialCounts> entry : roadLike.entrySet()) {
            String material = entry.getKey();
            SurveyStats.MaterialCounts counts = entry.getValue();
            double presence = share(counts.inRun(), runs);
            if (presence < DROP_BELOW_PRESENCE) {
                continue;
            }
            double centreShare = share(counts.centre(), roadCentreCells);
            double edgeShare = share(counts.runEnd(), runEndSlots);
            boolean ambiguous = share(counts.outsideRun(), samples) >= AMBIGUOUS_MIN_OUTSIDE_SHARE;
            RoadMaterialRole role;
            if (presence < ACCENT_MAX_PRESENCE) {
                role = RoadMaterialRole.ACCENT;
            } else if (edgeShare > 0 && edgeShare >= EDGE_RUN_END_FACTOR * centreShare) {
                role = RoadMaterialRole.EDGE;
            } else if (centreShare >= SURFACE_MIN_CENTRE_SHARE) {
                role = RoadMaterialRole.SURFACE;
            } else {
                role = RoadMaterialRole.ACCENT;
            }
            proposed.put(material, new ProposedProfile.Material(material, role, ambiguous, centreShare, edgeShare,
                counts.inRun()));
        }

        for (Map.Entry<String, SurveyStats.MaterialCounts> entry : stats.materials().entrySet()) {
            SurveyStats.MaterialCounts counts = entry.getValue();
            double overlayShare = share(counts.overlay(), samples);
            if (counts.overlay() == 0 || overlayShare < DROP_BELOW_PRESENCE) {
                continue;
            }
            String material = entry.getKey();
            ProposedProfile.Material floor = proposed.get(material);
            if (floor != null && share(floor.samples(), runs) >= overlayShare) {
                continue; // the same name is mostly a floor here; the floor role wins
            }
            proposed.put(material, new ProposedProfile.Material(material, RoadMaterialRole.OVERLAY, false, 0.0, 0.0,
                counts.overlay()));
        }

        List<ProposedProfile.Material> materials = new ArrayList<>(proposed.values());
        materials.sort(Comparator.comparing((ProposedProfile.Material m) -> m.role().ordinal())
            .thenComparing(ProposedProfile.Material::samples, Comparator.reverseOrder())
            .thenComparing(ProposedProfile.Material::material));

        int widthMin = Math.max(MIN_WIDTH, percentile(stats.widths(), WIDTH_MIN_PERCENTILE));
        int widthMax = Math.max(widthMin, percentile(stats.widths(), WIDTH_MAX_PERCENTILE));
        return new ProposedProfile(materials, widthMin, widthMax, samples);
    }

    /**
     * Nearest-rank percentile of a histogram (value → count): the value at rank
     * {@code ceil(p/100 × n)}; {@value #MIN_WIDTH} for an empty histogram.
     */
    static int percentile(SortedMap<Integer, Integer> histogram, int p) {
        long n = 0;
        for (int count : histogram.values()) {
            n += count;
        }
        if (n == 0) {
            return MIN_WIDTH;
        }
        long rank = Math.max(1, (long) Math.ceil(p / 100.0 * n));
        long cumulative = 0;
        int last = MIN_WIDTH;
        for (Map.Entry<Integer, Integer> entry : histogram.entrySet()) {
            cumulative += entry.getValue();
            last = entry.getKey();
            if (cumulative >= rank) {
                return last;
            }
        }
        return last;
    }

    private static double share(int part, int total) {
        return total == 0 ? 0.0 : (double) part / total;
    }
}
