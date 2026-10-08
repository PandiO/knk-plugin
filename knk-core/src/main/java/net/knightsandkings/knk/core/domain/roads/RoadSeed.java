package net.knightsandkings.knk.core.domain.roads;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * A stored road seed: a point the builder starts tracing from (DESIGN §3.4, §5.4). Mirrors the
 * web-api's {@code RoadSeedDto}. Bukkit-free.
 *
 * @param id        the API id
 * @param world     Bukkit world name
 * @param x         floor block x
 * @param y         floor block y
 * @param z         floor block z
 * @param source    admin-placed or dropped by a survey walk
 * @param surveyId  the survey that dropped it, if any
 * @param note      free text, or {@code null}
 * @param createdAt API timestamp
 */
public record RoadSeed(int id, String world, int x, int y, int z, RoadSeedSource source, OptionalInt surveyId,
                       String note, OffsetDateTime createdAt) {
    public RoadSeed {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(surveyId, "surveyId");
    }
}
