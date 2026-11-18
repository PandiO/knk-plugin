package net.knightsandkings.knk.core.navigation;

/**
 * The route cursor of {@code navigation.guidance-v2} (off-road navigation DESIGN § 7.5, decision O15): with these a
 * {@link NavigationSession} keeps a monotone {@code progress} and a {@code cursor} that follows the player both ways.
 *
 * @param cursorJump {@code navigation.cursor-jump}: a candidate more than this many blocks along the route from the
 *                   previous cursor is taken only when the previous cursor's candidate is farther than
 *                   {@code reroute-distance} (loops and switchbacks, § 7.5.6)
 */
public record CursorParameters(double cursorJump) {

    /** Default of {@code navigation.cursor-jump} (DESIGN § 7.6). */
    public static final double DEFAULT_CURSOR_JUMP = 12;

    public CursorParameters {
        if (!(cursorJump > 0)) {
            throw new IllegalArgumentException("cursorJump must be > 0");
        }
    }

    public static CursorParameters defaults() {
        return new CursorParameters(DEFAULT_CURSOR_JUMP);
    }
}
