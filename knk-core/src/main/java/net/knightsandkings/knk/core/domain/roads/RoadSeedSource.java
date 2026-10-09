package net.knightsandkings.knk.core.domain.roads;

/**
 * Who placed a road seed (DESIGN §3.4). Mirrors the web-api enum {@code RoadSeedSource}; serialised
 * by name.
 */
public enum RoadSeedSource {
    /** Placed by an admin ({@code /knk road seed}). */
    ADMIN,
    /** Dropped along a survey walk's breadcrumb (Phase 3 {@code breadcrumb-seed-spacing}). */
    SURVEY;

    /** The name the web-api uses ({@code Admin}, {@code Survey}). */
    public String apiName() {
        String lower = name().toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** Inverse of {@link #apiName()}; case-insensitive. */
    public static RoadSeedSource fromApiName(String apiName) {
        return valueOf(apiName.trim().toUpperCase());
    }
}
