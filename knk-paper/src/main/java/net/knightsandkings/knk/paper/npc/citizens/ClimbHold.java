package net.knightsandkings.knk.paper.npc.citizens;

/**
 * Keeps a climbing body in its ladder column (link H1, PandiO/knk-workspace#779). With its waypoint straight above or
 * below, Minecraft's move control lets a body on a ladder creep sideways, about 0.005 a tick in the
 * {@code npc-ladder-hatch} scenario. Past 0.2 off the column's middle, the body's side enters the next column, and at the
 * ladder's top its head catches on the roof block beside the hatch. The body is then stuck until the watchdog replans.
 * While it climbs toward a waypoint in its own column, its sideways speed is set to draw it back to the waypoint's
 * middle. Pure; the Bukkit side reads the blocks and sets the speed.
 */
final class ClimbHold {

    /** Closer than this to the waypoint's middle (across) the body is left alone. */
    static final double SLACK = 0.05;
    /** The share of the distance drawn back in a tick. */
    static final double PULL = 0.5;
    /** The largest sideways speed set (blocks a tick); a climbing body moves at most 0.15 across. */
    static final double MAX_SPEED = 0.1;

    private ClimbHold() {
    }

    /**
     * The sideways speed {x, z} that draws the body back to the waypoint's middle, or null when it is not needed: the
     * body does not climb, the waypoint is in another block column, or the body is close enough to it already.
     *
     * @param climbing whether the body's feet or head are in a block it climbs (a ladder, an open hatch over one)
     */
    static double[] pull(boolean climbing, double x, double z, double destX, double destZ) {
        if (!climbing || Math.floor(x) != Math.floor(destX) || Math.floor(z) != Math.floor(destZ)) {
            return null;
        }
        double dx = destX - x;
        double dz = destZ - z;
        if (dx * dx + dz * dz < SLACK * SLACK) {
            return null;
        }
        return new double[] {clamp(dx * PULL), clamp(dz * PULL)};
    }

    private static double clamp(double v) {
        return Math.max(-MAX_SPEED, Math.min(MAX_SPEED, v));
    }

    /**
     * A waypoint this many blocks or more below the feet, in their column, is a step down the ladder: Citizens counts a
     * waypoint reached only less than a block up or down, so it would keep steering the body towards it.
     */
    static final double DESCENT_BELOW = 0.9;
    /** Feet this close above a descent's last waypoint have landed. */
    static final double LANDED = 0.5;

    /**
     * The way down a ladder (PandiO/knk-workspace#956). Citizens steers a body towards a waypoint straight below it, a
     * few hundredths off in a one-block shaft, and every bump against the shaft's side makes Minecraft climb the body
     * up again: it bobs at the ladder's top. With no steering a ladder lets a body down by itself (0.15 a tick). So
     * with the body climbing and the waypoint at {@code from} at least {@link #DESCENT_BELOW} below it in its own block
     * column, this is the index of the last waypoint of the run straight down that column; Citizens stops steering
     * until the feet land there. -1 when there is no such step.
     *
     * @param waypoints the list Citizens follows, each {x, y, z} (feet)
     * @param from      the index of Citizens' current waypoint
     */
    static int descentEnd(boolean climbing, double x, double y, double z, java.util.List<double[]> waypoints,
                          int from) {
        if (!climbing || from < 0 || from >= waypoints.size()) {
            return -1;
        }
        double[] first = waypoints.get(from);
        if (!sameColumn(first, x, z) || first[1] > y - DESCENT_BELOW) {
            return -1;
        }
        int end = from;
        while (end + 1 < waypoints.size() && sameColumn(waypoints.get(end + 1), x, z)
            && waypoints.get(end + 1)[1] <= waypoints.get(end)[1]) {
            end++;
        }
        return end;
    }

    /** Whether feet at height {@code y} have come down to a descent's last waypoint {@code bottom}. */
    static boolean landed(double y, double[] bottom) {
        return y - bottom[1] < LANDED;
    }

    private static boolean sameColumn(double[] p, double x, double z) {
        return Math.floor(p[0]) == Math.floor(x) && Math.floor(p[2]) == Math.floor(z);
    }
}
