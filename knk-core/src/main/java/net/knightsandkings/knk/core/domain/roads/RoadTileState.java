package net.knightsandkings.knk.core.domain.roads;

/**
 * Whether a build replaces a tile's graph or only proposes changes (rev. 6 Part B, plan §5.7 D1).
 * Mirrors the web-api enum {@code RoadTileState}; serialised by name.
 */
public enum RoadTileState {
    /** The next build is uploaded directly; that upload curates the tile. */
    DETECTED,
    /** The stored graph is authoritative: a build makes a proposal the admin reviews. */
    CURATED;

    /** The name the web-api uses ({@code Detected}, {@code Curated}). */
    public String apiName() {
        String lower = name().toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** Inverse of {@link #apiName()}; case-insensitive. {@code null} or blank (an older API) is DETECTED. */
    public static RoadTileState fromApiName(String apiName) {
        if (apiName == null || apiName.isBlank()) {
            return DETECTED;
        }
        return valueOf(apiName.trim().toUpperCase());
    }
}
