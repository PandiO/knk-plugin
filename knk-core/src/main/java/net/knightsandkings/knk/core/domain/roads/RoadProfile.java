package net.knightsandkings.knk.core.domain.roads;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

import net.knightsandkings.knk.core.roads.survey.ProposedProfile;

/**
 * A road profile as the API stores it (DESIGN §3.1, plan D5/D8). Mirrors the web-api's
 * {@code RoadProfileDto}. Bukkit-free.
 *
 * <p>The materials are {@link ProposedProfile.Material}s (Phase 2b decision 11: the learner's
 * output and the API's profiles share one record). {@code statsJson} is the accumulated survey
 * statistics as the API stores them ({@code SurveyStats.fromJson(statsJson)} reads it; {@code null}
 * or {@code {}} = no surveys yet). The api-client's {@code RoadMapper} turns a profile into the
 * builder's {@code ProfileSet.Profile} and the router's {@code RoadNetworkSnapshot.Profile}.
 *
 * @param id             the API id
 * @param name           display name
 * @param roadClass      main / road / path (routing cost class)
 * @param costMultiplier routing cost multiplier, {@code > 0}
 * @param materials      learned materials with role, ambiguity and shares
 * @param widthMin       learned 5th-percentile width
 * @param widthMax       learned 95th-percentile width
 * @param sampleCount    survey samples accumulated into the stats
 * @param enabled        disabled profiles are ignored by the builder
 * @param scopeTownIds   Town domain ids the profile is limited to; empty = everywhere (plan D8)
 * @param statsJson      the API's opaque {@code stats} object as JSON text, or {@code null}
 * @param createdAt      API timestamp
 * @param updatedAt      API timestamp
 */
public record RoadProfile(int id, String name, RoadClass roadClass, double costMultiplier,
                          List<ProposedProfile.Material> materials, int widthMin, int widthMax, int sampleCount,
                          boolean enabled, List<Integer> scopeTownIds, String statsJson,
                          OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    public RoadProfile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(roadClass, "roadClass");
        materials = List.copyOf(Objects.requireNonNull(materials, "materials"));
        scopeTownIds = List.copyOf(Objects.requireNonNull(scopeTownIds, "scopeTownIds"));
    }

    /** Whether the profile carries accumulated survey statistics. */
    public boolean hasStats() {
        return statsJson != null && !statsJson.isBlank() && !statsJson.trim().equals("{}");
    }
}
