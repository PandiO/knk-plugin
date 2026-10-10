package net.knightsandkings.knk.core.domain.roads;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * The body of {@code POST api/road-seeds}. Mirrors the web-api's {@code RoadSeedCreateDto}.
 * Bukkit-free.
 *
 * @param world    Bukkit world name
 * @param x        floor block x
 * @param y        floor block y
 * @param z        floor block z
 * @param source   admin-placed or dropped by a survey walk
 * @param surveyId the survey that dropped it, if any
 * @param note     free text, or {@code null}
 */
public record RoadSeedCreate(String world, int x, int y, int z, RoadSeedSource source, OptionalInt surveyId,
                             String note) {
    public RoadSeedCreate {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(surveyId, "surveyId");
    }

    /** An admin seed ({@code /knk road seed}). */
    public static RoadSeedCreate admin(String world, int x, int y, int z, String note) {
        return new RoadSeedCreate(world, x, y, z, RoadSeedSource.ADMIN, OptionalInt.empty(), note);
    }

    /** A seed dropped along a survey's breadcrumb. */
    public static RoadSeedCreate survey(String world, int x, int y, int z, int surveyId) {
        return new RoadSeedCreate(world, x, y, z, RoadSeedSource.SURVEY, OptionalInt.of(surveyId), null);
    }
}
