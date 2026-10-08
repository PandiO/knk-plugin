package net.knightsandkings.knk.core.domain.roads;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * The body of {@code POST api/road-surveys} (Phase 3 survey session "Save"). Mirrors the web-api's
 * {@code RoadSurveyCreateDto}. Bukkit-free.
 *
 * @param world       Bukkit world name
 * @param profileId   the profile the survey is merged into, if any
 * @param startedAt   walk start
 * @param endedAt     walk end, or {@code null}
 * @param sampleCount samples taken
 * @param breadcrumb  the walked points
 * @param statsJson   this walk's {@code SurveyStats.toJson()}, or {@code null}
 */
public record RoadSurveyCreate(String world, OptionalInt profileId, OffsetDateTime startedAt, OffsetDateTime endedAt,
                               int sampleCount, List<RoadBreadcrumbPoint> breadcrumb, String statsJson) {
    public RoadSurveyCreate {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(startedAt, "startedAt");
        if (sampleCount < 0) {
            throw new IllegalArgumentException("sampleCount must be >= 0");
        }
        breadcrumb = List.copyOf(Objects.requireNonNull(breadcrumb, "breadcrumb"));
    }
}
