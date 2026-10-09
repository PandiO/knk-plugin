package net.knightsandkings.knk.core.domain.roads;

import java.util.List;
import java.util.Objects;

import net.knightsandkings.knk.core.roads.survey.ProposedProfile;

/**
 * The body of {@code POST api/road-profiles} / {@code PUT api/road-profiles/{id}} (plan D5: the
 * plugin's {@code ProfileLearner} recomputes the profile and PUTs it). Mirrors the web-api's
 * {@code RoadProfileUpsertDto}. Bukkit-free.
 *
 * @param name           display name
 * @param roadClass      main / road / path
 * @param costMultiplier routing cost multiplier, {@code > 0}
 * @param materials      the learned materials
 * @param widthMin       learned 5th-percentile width
 * @param widthMax       learned 95th-percentile width
 * @param sampleCount    survey samples accumulated into the stats
 * @param enabled        disabled profiles are ignored by the builder
 * @param scopeTownIds   Town domain ids the profile is limited to; empty = everywhere
 * @param statsJson      the merged {@code SurveyStats.toJson()}; {@code null} keeps the stored
 *                       stats on a PUT (Phase 1 contract)
 */
public record RoadProfileUpsert(String name, RoadClass roadClass, double costMultiplier,
                                List<ProposedProfile.Material> materials, int widthMin, int widthMax,
                                int sampleCount, boolean enabled, List<Integer> scopeTownIds, String statsJson) {
    public RoadProfileUpsert {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(roadClass, "roadClass");
        if (!(costMultiplier > 0)) {
            throw new IllegalArgumentException("costMultiplier must be > 0");
        }
        if (widthMin < 1 || widthMax < widthMin) {
            throw new IllegalArgumentException("widths must satisfy 1 <= widthMin <= widthMax, got "
                + widthMin + ".." + widthMax);
        }
        materials = List.copyOf(Objects.requireNonNull(materials, "materials"));
        scopeTownIds = List.copyOf(Objects.requireNonNull(scopeTownIds, "scopeTownIds"));
    }

    /**
     * The upsert a survey produces for an existing profile (Phase 2b status → 2e): materials,
     * widths and sample count from the learner, {@code stats} from the merged statistics, name,
     * class, cost, enabled and scope kept from the stored profile.
     */
    public static RoadProfileUpsert of(RoadProfile existing, ProposedProfile learned, String mergedStatsJson) {
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(learned, "learned");
        return new RoadProfileUpsert(existing.name(), existing.roadClass(), existing.costMultiplier(),
            learned.materials(), learned.widthMin(), learned.widthMax(), learned.sampleCount(), existing.enabled(),
            existing.scopeTownIds(), mergedStatsJson);
    }

    /**
     * The upsert for a brand-new profile from a survey: the admin supplies name, class, cost and
     * scope; enabled by default.
     */
    public static RoadProfileUpsert of(String name, RoadClass roadClass, double costMultiplier,
                                       List<Integer> scopeTownIds, ProposedProfile learned, String statsJson) {
        Objects.requireNonNull(learned, "learned");
        return new RoadProfileUpsert(name, roadClass, costMultiplier, learned.materials(), learned.widthMin(),
            learned.widthMax(), learned.sampleCount(), true, scopeTownIds, statsJson);
    }
}
