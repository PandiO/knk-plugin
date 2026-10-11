package net.knightsandkings.knk.core.domain.roads;

/**
 * Road class of a profile (DESIGN §2, §3.1): drives the routing cost factor ({@code class-cost},
 * DESIGN §4). Mirrors the web-api enum {@code RoadClass}; serialised by name.
 */
public enum RoadClass {
    /** Main streets and highways; cheapest to route over (default factor 0.9). */
    MAIN,
    /** Ordinary roads (factor 1.0). */
    ROAD,
    /** Paths and trails; slightly penalised (factor 1.15). */
    PATH;

    /** The name the web-api uses ({@code Main}, {@code Road}, {@code Path}). */
    public String apiName() {
        String lower = name().toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** Inverse of {@link #apiName()}; case-insensitive. */
    public static RoadClass fromApiName(String apiName) {
        return valueOf(apiName.trim().toUpperCase());
    }
}
