package net.knightsandkings.knk.core.roads.build;

import java.util.Objects;

/**
 * One build warning with the position it refers to (DESIGN §3.3 {@code RoadTile.Warnings}: cell cap
 * hit, unmatched seeds, anchors off the road …). {@link #text()} is what goes into the API's
 * {@code warnings[]} strings.
 *
 * @param message what happened, without the position
 * @param x       block position the warning is about
 * @param y       block position the warning is about
 * @param z       block position the warning is about
 */
public record BuildWarning(String message, int x, int y, int z) {
    public BuildWarning {
        Objects.requireNonNull(message, "message");
    }

    /** {@code "<message> at (x, y, z)"}. */
    public String text() {
        return message + " at (" + x + ", " + y + ", " + z + ")";
    }
}
