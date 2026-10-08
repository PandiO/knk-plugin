package net.knightsandkings.knk.core.domain.roads;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * A stored survey walk (DESIGN §3.2). Mirrors the web-api's {@code RoadSurveyDto}. Bukkit-free.
 *
 * @param id              the API id
 * @param world           Bukkit world name
 * @param profileId       the profile the survey was merged into, if any
 * @param startedByUserId the admin who walked it ({@code X-Acting-User-Id}), if sent
 * @param startedAt       walk start
 * @param endedAt         walk end, or {@code null}
 * @param sampleCount     samples taken
 * @param breadcrumb      the walked points (seeds and coverage checks read these)
 * @param statsJson       this walk's {@code SurveyStats.toJson()} as the API stores it, or {@code null}
 */
public record RoadSurvey(int id, String world, OptionalInt profileId, OptionalInt startedByUserId,
                         OffsetDateTime startedAt, OffsetDateTime endedAt, int sampleCount,
                         List<RoadBreadcrumbPoint> breadcrumb, String statsJson) {
    public RoadSurvey {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(startedByUserId, "startedByUserId");
        breadcrumb = List.copyOf(Objects.requireNonNull(breadcrumb, "breadcrumb"));
    }
}
