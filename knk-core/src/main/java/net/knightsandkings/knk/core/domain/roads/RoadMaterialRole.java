package net.knightsandkings.knk.core.domain.roads;

/**
 * Role of one material inside a road profile (DESIGN §5.1). Mirrors the web-api enum
 * {@code RoadMaterialRole}; serialised by name.
 * <p>Bukkit-free: materials are plain names (the paper side applies {@code Material.name()}).
 */
public enum RoadMaterialRole {
    /** The walking surface; dominates the middle of the road. */
    SURFACE,
    /** Borders and kerbs; mostly at the sides. */
    EDGE,
    /** Occasional variants and slope pieces. */
    ACCENT,
    /** Thin blocks lying on the road; the road cell is the block beneath. */
    OVERLAY;

    /** The name the web-api uses ({@code Surface}, {@code Edge}, {@code Accent}, {@code Overlay}). */
    public String apiName() {
        String lower = name().toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** Inverse of {@link #apiName()}; case-insensitive. */
    public static RoadMaterialRole fromApiName(String apiName) {
        return valueOf(apiName.trim().toUpperCase());
    }
}
