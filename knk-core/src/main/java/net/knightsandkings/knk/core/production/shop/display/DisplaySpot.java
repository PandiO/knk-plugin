package net.knightsandkings.knk.core.production.shop.display;

import java.util.Locale;

/**
 * A shop's display spot (display stalls P12, PandiO/knk-workspace#250, shops DESIGN § 7): a registered block where one of
 * the shop's items is shown as an item display with a floating price label ({@code ShopDisplaySpotListDto}). Spots are
 * registered explicitly, like storage containers, never found by scanning.
 *
 * @param pinnedCatalogueEntryId a catalogue entry of the same shop this spot always shows, or null
 * @param sortOrder              the order in which spots are filled (lowest first)
 */
public record DisplaySpot(int id, int shopId, String world, int x, int y, int z, Facing facing,
                          Integer pinnedCatalogueEntryId, int sortOrder, String label, boolean enabled) {

    /** Which way the item faces (the API's {@code ShopDisplayFacing}). */
    public enum Facing {
        NORTH(180f), EAST(-90f), SOUTH(0f), WEST(90f);

        private final float yaw;

        Facing(float yaw) {
            this.yaw = yaw;
        }

        /** The Minecraft yaw of an entity looking this way. */
        public float yaw() {
            return yaw;
        }

        public String apiName() {
            String name = name();
            return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
        }

        /** {@code North}, {@code east}, ...; South for anything else. */
        public static Facing parse(String text) {
            if (text != null) {
                for (Facing facing : values()) {
                    if (facing.name().equalsIgnoreCase(text.trim())) {
                        return facing;
                    }
                }
            }
            return SOUTH;
        }

        /**
         * The way an item faces a player who looks with {@code playerYaw} (the item turns towards the player, so it faces
         * the opposite of the player's look).
         */
        public static Facing towards(float playerYaw) {
            float yaw = ((playerYaw % 360f) + 360f) % 360f; // 0 = looking south, 90 = west, 180 = north, 270 = east
            if (yaw >= 315f || yaw < 45f) {
                return NORTH;
            }
            if (yaw < 135f) {
                return EAST;
            }
            if (yaw < 225f) {
                return SOUTH;
            }
            return WEST;
        }
    }

    public DisplaySpot {
        facing = facing == null ? Facing.SOUTH : facing;
    }

    public String blockKey() {
        return blockKey(world, x, y, z);
    }

    public static String blockKey(String world, int x, int y, int z) {
        return world + "|" + x + "|" + y + "|" + z;
    }

    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }

    /** How staff see the spot: {@code #3 "Front shelf" (world 10 64 -2)}. */
    public String describe() {
        return "#" + id + (label == null || label.isBlank() ? "" : " \"" + label + "\"") + " (" + world + " " + x + " " + y + " " + z + ")";
    }
}
