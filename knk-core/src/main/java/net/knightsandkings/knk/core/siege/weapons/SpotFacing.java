package net.knightsandkings.knk.core.siege.weapons;

import java.util.Locale;
import java.util.Optional;

/**
 * The direction a siege weapon spot faces (SIEGE_WEAPONS § 4.4, W5): one of the four compass directions, matching the
 * 90° rotations of a schematic authored facing north. The names match the API's {@code SiegeWeaponFacing}.
 */
public enum SpotFacing {
    NORTH("North"),
    EAST("East"),
    SOUTH("South"),
    WEST("West");

    private final String apiName;

    SpotFacing(String apiName) {
        this.apiName = apiName;
    }

    /** The value the API reads and writes ({@code "North"} ...). */
    public String apiName() {
        return apiName;
    }

    /** North and south point along the z axis, east and west along the x axis. */
    public boolean alongZ() {
        return this == NORTH || this == SOUTH;
    }

    /**
     * The facing nearest to a Minecraft yaw (W5 pre-fill from the admin's look direction). Minecraft's yaw is 0 when
     * looking south (+z), 90 west (-x), 180 north (-z) and 270 (or -90) east (+x); a yaw exactly between two
     * directions rounds clockwise (45 gives west).
     */
    public static SpotFacing fromYaw(float yaw) {
        double normalized = ((yaw % 360.0) + 360.0) % 360.0;
        int quarter = (int) Math.floor((normalized + 45.0) / 90.0) % 4;
        return switch (quarter) {
            case 0 -> SOUTH;
            case 1 -> WEST;
            case 2 -> NORTH;
            default -> EAST;
        };
    }

    /** Reads {@code north}, {@code n}, {@code East} ... (any case); empty for anything else. */
    public static Optional<SpotFacing> parse(String text) {
        if (text == null) return Optional.empty();
        String value = text.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "north", "n" -> Optional.of(NORTH);
            case "east", "e" -> Optional.of(EAST);
            case "south", "s" -> Optional.of(SOUTH);
            case "west", "w" -> Optional.of(WEST);
            default -> Optional.empty();
        };
    }

    public String displayName() {
        return apiName;
    }
}
