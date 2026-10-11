package net.knightsandkings.knk.core.roads.route;

import java.util.Objects;

/**
 * One instruction on a route (DESIGN §6.5), placed at a node.
 *
 * @param kind          the instruction
 * @param position      floor-block position of the node
 * @param along         polyline distance from the route start (what {@code Route.project} measures)
 * @param bearingChange signed heading change in degrees, positive = right (0 for level changes)
 * @param street        the street the instruction leads onto, or {@code null}
 * @param text          the player-facing sentence
 */
public record Maneuver(Kind kind, double[] position, double along, double bearingChange, String street, String text) {

    public enum Kind {
        /** Street changes without a turn: "Continue onto …". */
        CONTINUE,
        SLIGHT_LEFT,
        SLIGHT_RIGHT,
        LEFT,
        RIGHT,
        SHARP_LEFT,
        SHARP_RIGHT,
        /** The next edge drops more than 3 blocks: "Go down into the tunnel". */
        DOWN,
        /** The next edge climbs more than 3 blocks and stays up: "Take the stairs up". */
        UP,
        /** The next edge climbs more than 3 blocks and comes back down: "Cross the bridge". */
        BRIDGE;

        public boolean isTurn() {
            return this != CONTINUE && this != DOWN && this != UP && this != BRIDGE;
        }

        public boolean isLevelChange() {
            return this == DOWN || this == UP || this == BRIDGE;
        }
    }

    public Maneuver {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
        if (position == null || position.length != 3) {
            throw new IllegalArgumentException("position must be {x, y, z}");
        }
        position = position.clone();
    }

    /** The arrow to show for the turn (⬆ ⬈ ➡ ⬊ … are the paper side's; this is the direction word). */
    public String direction() {
        return switch (kind) {
            case SLIGHT_LEFT, LEFT, SHARP_LEFT -> "left";
            case SLIGHT_RIGHT, RIGHT, SHARP_RIGHT -> "right";
            default -> "straight";
        };
    }

    @Override
    public String toString() {
        return kind + "@" + String.format("%.1f", along) + " \"" + text + "\"";
    }
}
