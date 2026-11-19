package net.knightsandkings.knk.core.territory;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.knightsandkings.knk.core.map.RegionOutline;

/**
 * The territory lookup the API's {@code InsideTerritory} placement rule asks (production DESIGN § 3.4 P-2/P-3, P2c
 * PandiO/knk-workspace#677; contract in knk-web-api {@code Services/Production/TerritoryLookupClient.cs}). Bukkit-free:
 * it answers from a {@link TerritoryCache} and region outlines the platform reads.
 * <ol>
 *   <li>{@code contains-location}: whether the block column ({@code floor(x)}, {@code floor(z)}) is owned by the holder.</li>
 *   <li>{@code contains-region}: whether every block column of the region's union (main region and its sub-regions,
 *       KNG-257) is owned by the holder. Contested columns count as not owned (no owner). Large regions are sampled at
 *       most {@value #MAX_SAMPLES_PER_SIDE} columns per side, always including the outline's own points.</li>
 * </ol>
 * The answers' HTTP status: 200 with {@code {"inside": bool, ...}}; 400 for a bad request; 404 when the region does not
 * exist; 503 when territory is unavailable on this server ({@code production.enabled} off, or the cache not built yet),
 * which the API shows as "not checked" and never as a block.
 */
public final class TerritoryLookup {

    /** Columns sampled per side at most when a region is checked; smaller regions are checked column by column. */
    public static final int MAX_SAMPLES_PER_SIDE = 1024;

    /** One answer: its HTTP status and JSON body. */
    public record Answer(int status, Boolean inside, String reason, String holder, long columns, long notOwned,
                         TerritoryVerdict verdict) {

        public static Answer badRequest(String reason) {
            return new Answer(400, null, reason, null, 0, 0, null);
        }

        public static Answer notFound(String reason) {
            return new Answer(404, null, reason, null, 0, 0, null);
        }

        public static Answer unavailable(String reason) {
            return new Answer(503, null, reason, null, 0, 0, null);
        }

        /** The JSON body: {@code inside} on a 200, else {@code available} and the {@code reason}. */
        public String json() {
            StringBuilder json = new StringBuilder("{");
            if (status == 200) {
                json.append("\"inside\":").append(inside);
                json.append(",\"holder\":").append(quote(holder));
                json.append(",\"columns\":").append(columns);
                json.append(",\"notOwned\":").append(notOwned);
                if (verdict != null) {
                    json.append(",\"owner\":").append(verdict.owner() == null ? "null" : quote(verdict.owner().toString()));
                    json.append(",\"contested\":").append(verdict.isContested());
                }
            } else {
                json.append("\"inside\":null");
                json.append(",\"available\":").append(status != 503);
            }
            if (reason != null) {
                json.append(",\"reason\":").append(quote(reason));
            }
            return json.append('}').toString();
        }
    }

    /** A parsed request: the holder, the optional world, and the column for a location. */
    public record Request(TerritoryHolder holder, String world, Double x, Double z) {
    }

    private TerritoryLookup() {
    }

    /**
     * Reads {@code holderKind} ({@code Town}/{@code Kingdom}, any case), {@code holderId}, the optional {@code world} and,
     * with {@code needsLocation}, {@code x} and {@code z}. A {@link Answer#badRequest} names the first problem.
     */
    public static Object parse(Map<String, String> query, boolean needsLocation) {
        TerritoryHolder.Kind kind = TerritoryHolder.Kind.parse(query.get("holderKind"));
        if (kind == null) {
            return Answer.badRequest("holderKind must be Town or Kingdom");
        }
        int holderId;
        try {
            holderId = Integer.parseInt(String.valueOf(query.get("holderId")).trim());
        } catch (NumberFormatException e) {
            return Answer.badRequest("holderId must be a whole number");
        }
        String world = query.get("world");
        world = world == null || world.isBlank() ? null : world.trim();
        Double x = null;
        Double z = null;
        if (needsLocation) {
            try {
                x = Double.parseDouble(String.valueOf(query.get("x")).trim());
                z = Double.parseDouble(String.valueOf(query.get("z")).trim());
            } catch (NumberFormatException e) {
                return Answer.badRequest("x and z must be numbers");
            }
            if (!Double.isFinite(x) || !Double.isFinite(z)) {
                return Answer.badRequest("x and z must be numbers");
            }
        }
        return new Request(new TerritoryHolder(kind, holderId), world, x, z);
    }

    /**
     * Whether territory can answer at all: null when it can, else the 503.
     *
     * @param service null while {@code production.enabled} is off (no territory module)
     */
    public static Answer unavailable(TerritoryService service) {
        if (service == null) {
            return Answer.unavailable("territory is off on this game server (production.enabled: false)");
        }
        if (service.cache() == TerritoryCache.EMPTY) {
            return Answer.unavailable("the territory cache is not built yet");
        }
        return null;
    }

    /**
     * The block column ({@code floor(x)}, {@code floor(z)}) in {@code world}.
     *
     * @param world the request's world, else the server's default world (the caller passes it)
     */
    public static Answer location(TerritoryCache cache, TerritoryHolder holder, String world, double x, double z) {
        TerritoryVerdict verdict = cache.lookup(world, (int) Math.floor(x), (int) Math.floor(z));
        boolean owned = holder.equals(verdict.owner());
        return new Answer(200, owned, null, holder.toString(), 1, owned ? 0 : 1, verdict);
    }

    /**
     * Every block column of {@code union} (the region and its sub-regions, all in {@code union.get(0)}'s world).
     * Columns inside or on an outline count; with more than {@value #MAX_SAMPLES_PER_SIDE} columns on a side, an evenly
     * spaced sample that always includes the last column, plus every outline point.
     */
    public static Answer region(TerritoryCache cache, TerritoryHolder holder, List<RegionOutline> union) {
        if (union == null || union.isEmpty()) {
            return Answer.notFound("the region has no outline");
        }
        TerritoryShape shape = TerritoryShape.of(union);
        if (shape == null) {
            return Answer.notFound("the region has no outline");
        }
        String world = union.get(0).worldName();
        long columns = 0;
        long notOwned = 0;
        String firstNotOwned = null;
        for (RegionOutline outline : union) {
            for (var point : outline.points()) {
                columns++;
                if (!holder.equals(cache.lookup(world, point.x(), point.z()).owner())) {
                    notOwned++;
                    if (firstNotOwned == null) {
                        firstNotOwned = point.x() + "," + point.z();
                    }
                }
            }
        }
        int stepX = step(shape.maxX() - shape.minX() + 1);
        int stepZ = step(shape.maxZ() - shape.minZ() + 1);
        for (int x = shape.minX(); x <= shape.maxX(); x = next(x, stepX, shape.maxX())) {
            for (int z = shape.minZ(); z <= shape.maxZ(); z = next(z, stepZ, shape.maxZ())) {
                if (shape.distance(x, z) != 0) {
                    continue;
                }
                columns++;
                if (!holder.equals(cache.lookup(world, x, z).owner())) {
                    notOwned++;
                    if (firstNotOwned == null) {
                        firstNotOwned = x + "," + z;
                    }
                }
            }
        }
        String reason = notOwned == 0 ? null
                : notOwned + " of " + columns + " checked column(s) not owned by " + holder + " (first at x,z " + firstNotOwned + ")";
        return new Answer(200, notOwned == 0, reason, holder.toString(), columns, notOwned, null);
    }

    static int step(int size) {
        return Math.max(1, (int) Math.ceil(size / (double) MAX_SAMPLES_PER_SIDE));
    }

    /** The next sample, always ending on the last column. */
    static int next(int value, int step, int last) {
        if (value == last) {
            return last + 1;
        }
        return Math.min(value + step, last);
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
