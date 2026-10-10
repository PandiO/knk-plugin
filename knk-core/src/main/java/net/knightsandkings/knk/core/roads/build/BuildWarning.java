package net.knightsandkings.knk.core.roads.build;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /** {@code x} of a warning read back from text without a position ({@link #parse}). */
    public static final int NO_POSITION = Integer.MIN_VALUE;

    private static final Pattern TEXT = Pattern.compile("^(.*) at \\((-?\\d+), (-?\\d+), (-?\\d+)\\)$", Pattern.DOTALL);

    public BuildWarning {
        Objects.requireNonNull(message, "message");
    }

    /** {@code "<message> at (x, y, z)"}, or the bare message when it has no position. */
    public String text() {
        return x == NO_POSITION ? message : message + " at (" + x + ", " + y + ", " + z + ")";
    }

    /** Whether the warning carries a position (one read back from an API string may not). */
    public boolean hasPosition() {
        return x != NO_POSITION;
    }

    /**
     * Reads an API {@code warnings[]} string back (a stored tile's or proposal's warnings, re-uploaded
     * by a review step of a curated tile, plan §5.7): {@code parse(w.text()).text()} is {@code w.text()}.
     */
    public static BuildWarning parse(String text) {
        Objects.requireNonNull(text, "text");
        Matcher m = TEXT.matcher(text);
        if (m.matches()) {
            return new BuildWarning(m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
        }
        return new BuildWarning(text, NO_POSITION, 0, 0);
    }
}
