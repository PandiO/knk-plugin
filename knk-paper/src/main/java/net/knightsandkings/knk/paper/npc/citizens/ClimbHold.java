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
}
